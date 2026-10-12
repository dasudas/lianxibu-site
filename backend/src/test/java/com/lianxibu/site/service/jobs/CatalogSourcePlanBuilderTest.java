package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.sources.MemberUniverse;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import com.lianxibu.site.service.articles.*;
import com.lianxibu.site.service.sources.CollectionHandoffService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

class CatalogSourcePlanBuilderTest {
    MemberUniverse.Universe original;FrozenSourceUniverse.Reference ref;CatalogDedupEligibilityService.Selection selected;
    CollectionHandoffProofService.Proof collection;NewsCandidateRecall.CategoryHistory history;FrozenGenerationSettings settings;String category="agent";
    final Instant cutoff=Instant.parse("2026-01-02T00:00:00Z");final LocalDate day=LocalDate.of(2026,1,1);
    @BeforeEach void setup(){fixture(251,"Fictional retained catalog document");}
    static String namespace(String category){return switch(category){case "agent","workflow"->"github_repository";case "skill"->"github_skill";case "model"->"model_registry";default->"fictional";};}
    static String identity(String category,String value){return JdbcFrozenInputs.canonical(JsonMapper.shared().createObjectNode().put("namespace",namespace(category)).put("key",value));}
    void fixture(int count,String body){
        original=FrozenSourceUniverseTest.universe(count,false);
        var facts=new CollectionHandoffService.Facts(original.runId(),original.sourceId(),"daily",day,1,category,"production",1,"a".repeat(64),original.manifestHash(),count==0?"empty_success":original.excluded()>0?"partial":"complete","succeeded",count,original.accepted(),original.excluded());
        String owner=JdbcFrozenInputs.stableId("intent",CollectionHandoffProofService.logicalKey(facts)+":1");ref=FrozenSourceUniverse.encode(owner,original).reference();
        collection=new CollectionHandoffProofService.Proof(owner,"fictional-handoff","a".repeat(64),facts);settings=new FrozenGenerationSettings(1,List.of("Java"),List.of("Fictional"));
        var entries=new ArrayList<CatalogDedupEligibilityService.Entry>();
        for(var item:original.members()){
            if(item.terminalState().equals("excluded")){entries.add(new CatalogDedupEligibilityService.Entry(item.itemId(),CatalogDedupEligibilityService.Reason.COLLECTOR_EXCLUDED,null,null,null));continue;}
            var payload=JsonMapper.shared().createObjectNode().put("schema_version","1").put("category",category).put("title","Fictional catalog release").put("source_url","https://example.invalid/catalog/"+item.itemId());
            payload.set("identity",CanonicalJson.parse(identity(category,item.itemId())));payload.putNull("version").putNull("published_at").putNull("updated_at");
            payload.put("summary","Fictional retained catalog summary").put("document_text",body).put("truncated",false).put("parser_version","fictional-parser-v1").put("config_version",1);
            payload.putArray("evidence").addObject().put("url","https://example.invalid/catalog/"+item.itemId()).putNull("revision").putNull("path").putNull("file_hash").put("evidence_type","metadata");
            var fact=new SourceMemberEvidenceService.Fact(item.itemId(),2,category,JdbcFrozenInputs.canonical(payload.get("identity")),payload.path("title").asText(),payload.path("summary").asText(),JdbcFrozenInputs.canonical(payload));
            entries.add(new CatalogDedupEligibilityService.Entry(item.itemId(),CatalogDedupEligibilityService.Reason.ELIGIBLE,fact,day,cutoff.minusSeconds(1)));
        }
        selected=new CatalogDedupEligibilityService.Selection(original.runId(),original.sourceId(),category,day,cutoff,entries);
        String key=entries.stream().filter(e->e.fact()!=null).findFirst().map(e->e.fact().identityKey()).orElse(identity(category,"fictional-empty"));
        history=new NewsCandidateRecall.CategoryHistory(category,new NewsCandidateRecall.History(cutoff,List.of(new NewsCandidateRecall.Entity("fictional-candidate",key,"Fictional catalog release","Fictional old category summary",1,cutoff.minusSeconds(10),cutoff,"pending",false))));
    }
    SourcePartitionPlanner.Plan build(){return new CatalogSourcePlanBuilder().build(original,ref,selected,collection,settings,history);}
    @Test void fourCategoriesFreezeEveryOriginalMemberIntoTheirOwnStageAndCompleteRequests(){
        for(String c:List.of("agent","skill","workflow","model")){category=c;fixture(251,"Fictional retained catalog document");var plan=build();
            assertEquals(c+"_dedup",plan.stage().name().toLowerCase(Locale.ROOT));assertEquals(List.of(100,26),plan.chunks().stream().map(chunk->chunk.itemIds().size()).toList());assertEquals(251,plan.decisions().size());
            assertEquals(126,plan.chunks().stream().flatMap(chunk->chunk.itemIds().stream()).distinct().count());
            for(var chunk:plan.chunks()){
                var node=CanonicalJson.parse(chunk.snapshotJson());var context=FrozenApplicationContext.fromJson(node.get(FrozenApplicationContext.FIELD));var input=context.input(chunk.rootId(),"fictional-attempt",chunk.inputHash(),0);
                assertEquals(plan.stage(),context.stage());assertEquals(Set.copyOf(chunk.itemIds()),input.itemIds());assertEquals("partial",node.path("coverage_state").asText());assertEquals("collection:"+original.runId(),node.path("missing").get(0).asText());
                assertEquals(settings,FrozenGenerationSettings.read(node.get(FrozenGenerationSettings.FIELD)));assertEquals(List.of(collection),FrozenCollectionManifest.read(node.get(FrozenCollectionManifest.FIELD)));
                assertEquals(c,node.get(NewsCandidateRecall.FIELD).path("category").asText());assertEquals(1,node.get(NewsCandidateRecall.FIELD).path("scanned_entities").asInt());assertEquals(chunk.itemIds().size(),node.get(NewsCandidateRecall.FIELD).path("members").size());
                var bindings=FrozenMemberBindings.read(node,input);assertEquals(chunk.itemIds().size(),bindings.size());assertTrue(bindings.values().stream().allMatch(f->f.category().equals(c)));
                assertTrue(input.versionEligibleItems().isEmpty());
                for(var member:node.get("members")){assertTrue(member.path("body").get("published_at").isNull());assertEquals(day.toString(),member.path("business_date").asText());assertEquals(CanonicalJson.hash(CanonicalJson.Domain.CONTENT,member.get("body")),member.path("body_hash").asText());}
                assertEquals(chunk.requestJson(),new GenerationRequestBuilder().build(plan.stage(),chunk.snapshotJson(),chunk.inputHash()));
            }assertEquals(plan,build());
        }
    }
    @Test void missingDecisionsForeignHistoryChangedCollectionAndOwnerCannotGrantPartialPlan(){
        var prior=selected;selected=new CatalogDedupEligibilityService.Selection(prior.runId(),prior.sourceId(),category,day,cutoff,prior.entries().subList(1,prior.entries().size()));assertThrows(RuntimeException.class,this::build);selected=prior;
        selected=new CatalogDedupEligibilityService.Selection(prior.runId(),prior.sourceId(),"workflow",day,cutoff,prior.entries());assertThrows(RuntimeException.class,this::build);selected=prior;
        var old=history;history=new NewsCandidateRecall.CategoryHistory("workflow",history.facts());assertThrows(RuntimeException.class,this::build);history=old;
        var oldRef=ref;ref=FrozenSourceUniverse.encode("fictional-other",original).reference();assertThrows(RuntimeException.class,this::build);ref=oldRef;
        collection=new CollectionHandoffProofService.Proof(collection.intentId(),collection.dispositionId(),collection.payloadHash(),new CollectionHandoffService.Facts(original.runId(),original.sourceId(),"daily",day,1,"skill","production",1,"a".repeat(64),original.manifestHash(),original.excluded()>0?"partial":"complete","succeeded",251,original.accepted(),original.excluded()));assertThrows(RuntimeException.class,this::build);
    }
    @Test void forgedIdentityCategoryTitleAndSummaryCannotReplaceOriginalRetainedEvidence(){
        var prior=selected;var entry=prior.entries().getFirst();var fact=entry.fact();
        for(String mode:List.of("identity","title","summary","category")){
            var changed=new SourceMemberEvidenceService.Fact(fact.id(),fact.revision(),mode.equals("category")?"workflow":fact.category(),mode.equals("identity")?identity(category,"fictional-other"):fact.identityKey(),mode.equals("title")?"Fictional replacement title":fact.title(),mode.equals("summary")?"Fictional replacement summary":fact.summary(),fact.payloadJson());
            var entries=new ArrayList<>(prior.entries());entries.set(0,new CatalogDedupEligibilityService.Entry(entry.itemId(),entry.reason(),changed,day,entry.completedAt()));selected=new CatalogDedupEligibilityService.Selection(prior.runId(),prior.sourceId(),category,day,cutoff,entries);assertThrows(RuntimeException.class,this::build);
        }selected=prior;
    }
    @Test void noEligibleOriginalInputsKeepCompleteReasonsAndScopeWithoutModelChunks(){
        var entries=selected.entries().stream().map(e->e.reason()==CatalogDedupEligibilityService.Reason.ELIGIBLE?new CatalogDedupEligibilityService.Entry(e.itemId(),CatalogDedupEligibilityService.Reason.NOT_AI_ELIGIBLE,null,null,null):e).toList();
        selected=new CatalogDedupEligibilityService.Selection(original.runId(),original.sourceId(),category,day,cutoff,entries);var plan=build();assertTrue(plan.chunks().isEmpty());assertEquals(251,plan.decisions().size());assertEquals(cutoff,plan.scope().cutoff());assertEquals(settings,plan.scope().settings());
    }
    @Test void completeJsonEscapingAndCandidateBodiesMustFitActualRequestBudget(){
        fixture(1,"\"".repeat(90000));var candidates=new ArrayList<NewsCandidateRecall.Entity>();
        for(int n=0;n<20;n++)candidates.add(new NewsCandidateRecall.Entity("fictional-candidate-"+n,identity(category,"fictional-"+n),"Fictional catalog release","\"".repeat(16384),1,cutoff.minusSeconds(1),cutoff,"pending",false));
        history=new NewsCandidateRecall.CategoryHistory(category,new NewsCandidateRecall.History(cutoff,candidates));assertThrows(GenerationFlowService.CapacityGuarded.class,this::build);
    }
}
