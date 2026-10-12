package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.domain.sources.MemberUniverse;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import com.lianxibu.site.service.articles.*;
import com.lianxibu.site.service.sources.CollectionHandoffService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/** Native issued old/current qualifications, combined with explicit pure source-plan fixture facts. No native whole-handoff grant. */
class CatalogVersionSourcePlanTest extends CatalogVersionJudgmentValueTest {
    record Frame(MemberUniverse.Universe universe,FrozenSourceUniverse.Reference ref,CatalogDedupEligibilityService.Selection selection,
            CollectionHandoffProofService.Proof collection,FrozenGenerationSettings settings,NewsCandidateRecall.CategoryHistory history,
            Map<String,CatalogVersionQualificationProjection.Projection> versions){ }
    Frame frame(int count){
        var members=new ArrayList<MemberUniverse.Member>();var parts=new ArrayList<MemberUniverse.Part>();var entries=new ArrayList<CatalogDedupEligibilityService.Entry>();var versions=new TreeMap<String,CatalogVersionQualificationProjection.Projection>();var day=LocalDate.of(2026,1,1);String identity=JdbcFrozenInputs.canonical(current.get("identity"));long entityRevision=f.jdbc.queryForObject("SELECT revision FROM analyzed_entities",Long.class);
        for(int n=0;n<count;n++){
            String suffix=String.format(Locale.ROOT,"%03d",n),id="fictional-version-plan-"+suffix,sourceItem="fictional-version-source-"+suffix;
            f.jdbc.update("INSERT INTO source_run_items SELECT ?,run_id,source_id,revision,category,created_at,terminal_state,state,projection_decision,effective_valid,evidence_json,evidence_hash,entity_id,? FROM source_run_items WHERE id='fictional-current-item'",id,sourceItem);
            var p=service.readProjection(f.category,"fictional-entity","fictional-analysis",id,f.clock.instant()).orElseThrow();versions.put(id,p);
            members.add(new MemberUniverse.Member(id,"fictional-member-"+suffix,sourceItem,"fictional-observation-"+suffix,CatalogPreviousAnalysisTest.Fixture.sha(CanonicalJson.bytes(current)),null,n/100,"accepted"));
            var fact=new SourceMemberEvidenceService.Fact(id,p.toJson().get("current_proof").get("member_revision").asLong(),f.category,identity,current.get("title").asText(),current.get("summary").isNull()?null:current.get("summary").asText(),JdbcFrozenInputs.canonical(current));entries.add(new CatalogDedupEligibilityService.Entry(id,CatalogDedupEligibilityService.Reason.ELIGIBLE,fact,day,f.clock.instant().minusSeconds(1)));
        }
        for(int n=0;n<(count+99)/100;n++)parts.add(new MemberUniverse.Part(n,"a".repeat(64),Math.min(100,count-n*100)));
        var universe=new MemberUniverse.Universe("fictional-run","fictional-source","a".repeat(64),parts,members,count,0);
        var facts=new CollectionHandoffService.Facts(universe.runId(),universe.sourceId(),"daily",day,1,f.category,"production",1,"a".repeat(64),universe.manifestHash(),count==0?"empty_success":"complete","succeeded",count,count,0);
        String intent=JdbcFrozenInputs.stableId("intent",CollectionHandoffProofService.logicalKey(facts)+":1");var ref=FrozenSourceUniverse.encode(intent,universe).reference();var collection=new CollectionHandoffProofService.Proof(intent,"fictional-handoff","a".repeat(64),facts);
        var selection=new CatalogDedupEligibilityService.Selection(universe.runId(),universe.sourceId(),f.category,day,f.clock.instant(),entries);
        var history=new NewsCandidateRecall.CategoryHistory(f.category,new NewsCandidateRecall.History(f.clock.instant(),List.of(new NewsCandidateRecall.Entity("fictional-entity",identity,"Fictional catalog release","Fictional old catalog summary",entityRevision,f.clock.instant().minusSeconds(2),f.clock.instant(),"analyzed",false))));
        return new Frame(universe,ref,selection,collection,new FrozenGenerationSettings(1,List.of(),List.of()),history,Map.copyOf(versions));
    }
    SourcePartitionPlanner.Plan build(Frame frame,Map<String,CatalogVersionQualificationProjection.Projection> versions){return new CatalogSourcePlanBuilder().buildWithVersions(frame.universe(),frame.ref(),frame.selection(),frame.collection(),frame.settings(),frame.history(),versions);}
    @Test void FourCategoriesFreezeFullNativeQualificationAndTypedReferencesInsideOriginalRequestHash(){
        for(String category:List.of("agent","skill","workflow","model")){
            initialize(category,false);var frame=frame(1);var plan=build(frame,frame.versions());assertEquals(1,plan.chunks().size());var chunk=plan.chunks().getFirst();var node=CanonicalJson.parse(chunk.snapshotJson());var input=FrozenApplicationContext.fromJson(node.get(FrozenApplicationContext.FIELD)).input(chunk.rootId(),"fictional-attempt",chunk.inputHash(),0);assertEquals("2",input.dedupResultVersion());var qualified=FrozenCatalogVersionProjections.read(node.get(FrozenCatalogVersionProjections.FIELD),input);
            assertEquals(1,qualified.entries().size());var p=frame.versions().values().iterator().next();assertTrue(qualified.entries().get(p.memberId()).matches(p));assertEquals(p.resultConstraint(),input.catalogVersionQualifications().get(p.memberId()));assertEquals(chunk.inputHash(),CanonicalJson.hash(CanonicalJson.Domain.INPUT,node));var request=CanonicalJson.parse(chunk.requestJson());assertEquals("site-catalog-dedup-v2",request.get("prompt_version").asText());assertEquals(CanonicalJson.bytes(node).length,CanonicalJson.bytes(CanonicalJson.parse(request.get("messages").get(1).get("content").asText())).length);assertEquals(1,f.sends);
        }
    }
    @Test void ActualHundredAndOneLeafQualificationsRemainCompleteAcrossOriginalBoundedPartitions(){
        var frame=frame(101);var plan=build(frame,frame.versions());var seen=new HashSet<String>();for(var chunk:plan.chunks()){
            var node=CanonicalJson.parse(chunk.snapshotJson());var input=FrozenApplicationContext.fromJson(node.get(FrozenApplicationContext.FIELD)).input(chunk.rootId(),"fictional-attempt",chunk.inputHash(),0);var versions=FrozenCatalogVersionProjections.read(node.get(FrozenCatalogVersionProjections.FIELD),input);assertEquals(Set.copyOf(chunk.itemIds()),versions.entries().keySet());assertTrue(chunk.itemIds().size()<=100);for(var id:chunk.itemIds()){assertTrue(seen.add(id));assertTrue(versions.entries().get(id).matches(frame.versions().get(id)));}
        }assertEquals(frame.versions().keySet(),seen);assertTrue(seen.contains("fictional-version-plan-100"));assertEquals(101,plan.decisions().size());assertEquals(1,f.sends);assertEquals(1,f.jdbc.queryForObject("SELECT analysis_generation FROM analyzed_entities",Integer.class));
    }
    @Test void UnknownOmittedQualificationsAndKnownFalseRemainDistinctFromChangedEligibility(){
        var frame=frame(1);var unknown=build(frame,Map.of());var node=CanonicalJson.parse(unknown.chunks().getFirst().snapshotJson());var context=FrozenApplicationContext.fromJson(node.get(FrozenApplicationContext.FIELD));assertEquals("2",context.input("fictional-root","fictional-attempt","c".repeat(64),0).dedupResultVersion());assertTrue(node.get(FrozenCatalogVersionProjections.FIELD).get("items").isEmpty());assertTrue(node.get(FrozenApplicationContext.FIELD).get("version_eligible_item_ids").isEmpty());
        initialize("agent",false);current=f.catalogPayload();update();frame=frame(1);var unchanged=build(frame,frame.versions());node=CanonicalJson.parse(unchanged.chunks().getFirst().snapshotJson());assertFalse(node.get(FrozenApplicationContext.FIELD).get("catalog_version_qualifications").get(0).get("version_changed").booleanValue());assertTrue(node.get(FrozenApplicationContext.FIELD).get("version_eligible_item_ids").isEmpty());assertEquals(1,node.get(FrozenCatalogVersionProjections.FIELD).get("items").size());assertEquals(1,f.sends);
    }
    @Test void AliasedMemberLastBodyMismatchAndChangedNativeEntityRevisionRejectEntireSourcePlan(){
        var frame=frame(2);var first=frame.versions().values().iterator().next();assertThrows(RuntimeException.class,()->build(frame,Map.of("fictional-alias",first)));
        var entries=new ArrayList<>(frame.selection().entries());var last=entries.getLast();var fact=last.fact();var changed=(ObjectNode)CanonicalJson.parse(fact.payloadJson());changed.put("document_text","Fictional changed last source document");entries.set(entries.size()-1,new CatalogDedupEligibilityService.Entry(last.itemId(),last.reason(),new SourceMemberEvidenceService.Fact(fact.id(),fact.revision(),fact.category(),fact.identityKey(),fact.title(),fact.summary(),JdbcFrozenInputs.canonical(changed)),last.businessDate(),last.completedAt()));var stale=new Frame(frame.universe(),frame.ref(),new CatalogDedupEligibilityService.Selection(frame.selection().runId(),frame.selection().sourceId(),frame.selection().category(),frame.selection().day(),frame.selection().cutoff(),entries),frame.collection(),frame.settings(),frame.history(),frame.versions());assertThrows(RuntimeException.class,()->build(stale,stale.versions()));
        var entity=frame.history().facts().entities().getFirst();var history=new NewsCandidateRecall.CategoryHistory(f.category,new NewsCandidateRecall.History(f.clock.instant(),List.of(new NewsCandidateRecall.Entity(entity.id(),entity.identityKey(),entity.title(),entity.summary(),entity.revision()+1,entity.firstRecordedAt(),entity.metadataAsOf(),entity.analysisState(),entity.legacy()))));var changedHistory=new Frame(frame.universe(),frame.ref(),frame.selection(),frame.collection(),frame.settings(),history,frame.versions());assertThrows(RuntimeException.class,()->build(changedHistory,changedHistory.versions()));assertEquals(1,f.sends);
    }
    @Test void OriginalV1SourcePlansRetainExactDefaultContextAndRequests(){
        var frame=frame(1);var old=new CatalogSourcePlanBuilder().build(frame.universe(),frame.ref(),frame.selection(),frame.collection(),frame.settings(),frame.history());var node=CanonicalJson.parse(old.chunks().getFirst().snapshotJson());assertFalse(node.has(FrozenCatalogVersionProjections.FIELD));assertFalse(node.get(FrozenApplicationContext.FIELD).has("dedup_result_version"));assertEquals("site-generation-v1",CanonicalJson.parse(old.chunks().getFirst().requestJson()).get("prompt_version").asText());assertEquals(old,new CatalogSourcePlanBuilder().build(frame.universe(),frame.ref(),frame.selection(),frame.collection(),frame.settings(),frame.history()));assertEquals(1,f.sends);
    }
}
