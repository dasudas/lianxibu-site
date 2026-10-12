package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.sources.MemberUniverse;
import com.lianxibu.site.service.articles.*;
import com.lianxibu.site.service.sources.CollectionHandoffService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.node.ObjectNode;

class NewsSourcePlanBuilderTest {
    MemberUniverse.Universe original;FrozenSourceUniverse.Reference ref;NewsDedupEligibilityService.Selection selected;
    CollectionHandoffProofService.Proof collection;NewsCandidateRecall.History history;FrozenGenerationSettings settings;
    final Instant cutoff=Instant.parse("2026-01-02T00:00:00Z");final LocalDate day=LocalDate.of(2026,1,1);
    @BeforeEach void setup(){fixture(251,"Fictional source body");}
    void fixture(int count,String body){
        original=FrozenSourceUniverseTest.universe(count,false);
        var facts=new CollectionHandoffService.Facts(original.runId(),original.sourceId(),"daily",day,1,"news_tech","production",1,"a".repeat(64),original.manifestHash(),original.excluded()>0?"partial":"complete","succeeded",count,original.accepted(),original.excluded());
        String owner=com.lianxibu.site.repository.jobs.JdbcFrozenInputs.stableId("intent",CollectionHandoffProofService.logicalKey(facts)+":1");ref=FrozenSourceUniverse.encode(owner,original).reference();
        collection=new CollectionHandoffProofService.Proof(owner,"fictional-handoff","a".repeat(64),facts);settings=new FrozenGenerationSettings(1,List.of("Java"),List.of("Fictional"));
        var entries=new ArrayList<NewsDedupEligibilityService.Entry>();
        for(var item:original.members()){
            if(item.terminalState().equals("excluded")){entries.add(new NewsDedupEligibilityService.Entry(item.itemId(),NewsDedupEligibilityService.Reason.COLLECTOR_EXCLUDED,null,null,null));continue;}
            var payload=(ObjectNode)CanonicalJson.parse(SourceMemberEvidenceServiceTest.PAYLOAD);payload.put("title","Fictional Java release");payload.put("body_text",body);payload.put("published_at","2026-01-01T08:00:00.000000Z");payload.put("time_basis","published");payload.put("source_url","https://example.invalid/story/"+item.itemId());
            var identity=tools.jackson.databind.json.JsonMapper.shared().createObjectNode();identity.put("namespace","article_url");identity.set("key",payload.get("source_url"));
            var fact=new SourceMemberEvidenceService.Fact(item.itemId(),2,"news_tech",com.lianxibu.site.repository.jobs.JdbcFrozenInputs.canonical(identity),payload.path("title").asText(),payload.path("source_summary").asText(),com.lianxibu.site.repository.jobs.JdbcFrozenInputs.canonical(payload));
            entries.add(new NewsDedupEligibilityService.Entry(item.itemId(),NewsDedupEligibilityService.Reason.ELIGIBLE,fact,day,cutoff.minusSeconds(1)));
        }
        selected=new NewsDedupEligibilityService.Selection(original.runId(),original.sourceId(),day,cutoff,entries);
        String key=entries.stream().filter(e->e.fact()!=null).findFirst().map(e->e.fact().identityKey()).orElse("{\"key\":\"fictional\",\"namespace\":\"fictional\"}");
        history=new NewsCandidateRecall.History(cutoff,List.of(new NewsCandidateRecall.Entity("fictional-candidate",key,"Fictional Java release","Fictional old entity summary",1,cutoff.minusSeconds(10),cutoff,"pending",false)));
    }
    SourcePartitionPlanner.Plan build(){return new NewsSourcePlanBuilder().build(original,ref,selected,collection,settings,history);}
    @Test void allOriginalEligibleFactsMetadataCandidateScopeAndActualRequestsAreFrozenOnce(){
        var plan=build();assertEquals(List.of(100,26),plan.chunks().stream().map(c->c.itemIds().size()).toList());assertEquals(251,plan.decisions().size());
        assertEquals(126,plan.chunks().stream().flatMap(c->c.itemIds().stream()).distinct().count());
        for(var chunk:plan.chunks()){
            var node=CanonicalJson.parse(chunk.snapshotJson());var context=FrozenApplicationContext.fromJson(node.get(FrozenApplicationContext.FIELD));var input=context.input(chunk.rootId(),"fictional-attempt",chunk.inputHash(),0);
            assertEquals(Set.copyOf(chunk.itemIds()),input.itemIds());assertEquals("partial",node.path("coverage_state").asText());assertEquals("collection:"+original.runId(),node.path("missing").get(0).asText());
            assertEquals(settings,FrozenGenerationSettings.read(node.get(FrozenGenerationSettings.FIELD)));assertEquals(List.of(collection),FrozenCollectionManifest.read(node.get(FrozenCollectionManifest.FIELD)));
            assertEquals(NewsCandidateRecall.VERSION,node.get(NewsCandidateRecall.FIELD).path("retrieval_version").asText());assertEquals(1,node.get(NewsCandidateRecall.FIELD).path("scanned_entities").asInt());
            assertEquals(chunk.itemIds().size(),node.get(NewsCandidateRecall.FIELD).path("members").size());
            var bindings=FrozenMemberBindings.read(node,input);assertEquals(chunk.itemIds().size(),bindings.size());
            for(var item:node.get("members")){assertEquals("source_member",item.path("kind").asText());assertEquals(day.toString(),item.path("business_date").asText());assertEquals(cutoff.minusSeconds(1),Instant.parse(item.path("completed_at").asText()));assertEquals(CanonicalJson.hash(CanonicalJson.Domain.CONTENT,item.get("body")),item.path("body_hash").asText());}
            assertEquals(chunk.requestJson(),new GenerationRequestBuilder().build(plan.stage(),chunk.snapshotJson(),chunk.inputHash()));
            assertEquals(1,input.candidatesByItem().values().iterator().next().size());
        }
        assertEquals(plan,build());assertEquals(1,plan.chunks().getLast().stagePart());
    }
    @Test void missingDecisionsMixedScopeAndAnotherOwnerCannotGrantAPartialPlan(){
        var prior=selected;selected=new NewsDedupEligibilityService.Selection(prior.runId(),prior.sourceId(),day,cutoff,prior.entries().subList(1,prior.entries().size()));assertThrows(RuntimeException.class,this::build);
        selected=new NewsDedupEligibilityService.Selection(prior.runId(),"fictional-other",day,cutoff,prior.entries());assertThrows(RuntimeException.class,this::build);selected=prior;
        var originalRef=ref;ref=FrozenSourceUniverse.encode("fictional-other-owner",original).reference();assertThrows(RuntimeException.class,this::build);ref=originalRef;
        history=new NewsCandidateRecall.History(cutoff.plusSeconds(1),List.of());assertThrows(RuntimeException.class,this::build);
    }
    @Test void aFactWithForgedIdentityTitleSummaryOrCategoryCannotBecomeInput(){
        var prior=selected;var entry=prior.entries().getFirst();var fact=entry.fact();
        for(String mode:List.of("identity","title","summary","category")){
            var changed=new SourceMemberEvidenceService.Fact(fact.id(),fact.revision(),mode.equals("category")?"agent":fact.category(),mode.equals("identity")?"{\"key\":\"other\",\"namespace\":\"fictional\"}":fact.identityKey(),mode.equals("title")?"Changed title":fact.title(),mode.equals("summary")?"Changed summary":fact.summary(),fact.payloadJson());
            var entries=new ArrayList<>(prior.entries());entries.set(0,new NewsDedupEligibilityService.Entry(entry.itemId(),entry.reason(),changed,day,entry.completedAt()));selected=new NewsDedupEligibilityService.Selection(prior.runId(),prior.sourceId(),day,cutoff,entries);assertThrows(RuntimeException.class,this::build);
        }selected=prior;
    }
    @Test void zeroEligibleMembersKeepTheirOriginalReasonsAndScopeWithoutAiChunks(){
        var entries=selected.entries().stream().map(e->e.reason()==NewsDedupEligibilityService.Reason.ELIGIBLE?new NewsDedupEligibilityService.Entry(e.itemId(),NewsDedupEligibilityService.Reason.NOT_AI_ELIGIBLE,null,null,null):e).toList();
        selected=new NewsDedupEligibilityService.Selection(original.runId(),original.sourceId(),day,cutoff,entries);var plan=build();assertTrue(plan.chunks().isEmpty());assertEquals(251,plan.decisions().size());assertEquals(cutoff,plan.scope().cutoff());assertEquals(settings,plan.scope().settings());
    }
    @Test void fullCandidateJsonEscapingCanExceedTheActualRequestBudgetAndMustGuardTheWholePlan(){
        fixture(1,"\"".repeat(50000));var candidates=new ArrayList<NewsCandidateRecall.Entity>();
        for(int n=0;n<20;n++)candidates.add(new NewsCandidateRecall.Entity("fictional-candidate-"+n,"{\"key\":\"fictional-"+n+"\",\"namespace\":\"fictional\"}","Fictional Java release","\"".repeat(16384),1,cutoff.minusSeconds(1),cutoff,"pending",false));
        history=new NewsCandidateRecall.History(cutoff,candidates);assertThrows(GenerationFlowService.CapacityGuarded.class,this::build);
    }
}
