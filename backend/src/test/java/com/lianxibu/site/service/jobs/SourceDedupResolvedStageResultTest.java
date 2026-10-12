package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.repository.sources.*;
import com.lianxibu.site.service.sources.*;
import com.lianxibu.site.service.articles.SourceMemberEvidenceServiceTest;
import java.sql.Timestamp;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Original collector manifests and fixed preparation, retained fictional results; no external call. */
class SourceDedupResolvedStageResultTest {
    SourceAnalysisParentStepTest f;SourcePartitionPlanner.Plan plan;FrozenSourceStagePlan.Reference ref;
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    String runId(){return "fictional-run";}
    boolean initializeRegisteredRun(String id){return false;}
    void setup(int count) throws Exception {setup(count,0);}
    void setup(int count,int padding) throws Exception {
        f=new SourceAnalysisParentStepTest(){@Override GenerationJdbcStoreTest database(){return SourceDedupResolvedStageResultTest.this.database();}};f.newSource();
        for(String table:List.of("jobs","source_run_items","request_receipts","source_runs"))f.db.jdbc.update("DELETE FROM "+table);
        String run=runId();boolean preRegistered=initializeRegisteredRun(run);
        long configVersion=preRegistered?f.db.jdbc.queryForObject("SELECT config_version FROM source_runs WHERE id=?",Long.class,run):1;
        var manifest=new ArrayList<RunManifestService.Part>();
        for(int part=0;part<(count+99)/100;part++){
            var members=new ArrayList<RunManifestService.Member>();for(int n=part*100;n<Math.min(count,(part+1)*100);n++){
                String suffix=String.format(Locale.ROOT,"%04d",n);var payload=(ObjectNode)CanonicalJson.parse(SourceMemberEvidenceServiceTest.PAYLOAD);payload.put("title","Fictional source title "+suffix).put("source_url","https://example.invalid/story/"+suffix).put("published_at","2026-01-01T08:00:00.000000Z").put("time_basis","published");
                payload.put("config_version",configVersion);
                String body=JdbcFrozenInputs.canonical(payload),hash=CanonicalJson.hash(CanonicalJson.Domain.CONTENT,payload); // Transport hash is retained independently of evidence SHA-256.
                var member=new RunManifestService.Member("fictional-member-"+suffix,"fictional-key-"+suffix+"F".repeat(padding),"fictional-observation-"+suffix,hash,null);members.add(member);
                f.db.jdbc.update("INSERT INTO source_run_items(id,run_id,source_id,member_id,source_item_id,observation_id,payload_hash,part_no,terminal_state,category,state,projection_decision,effective_valid,revision,created_at,evidence_json,evidence_hash) VALUES(?,?,'fictional-source',?,?,?,?,?,'accepted','news_tech','pending','initialized',1,2,?,?,?)",
                    "fictional-item-"+suffix,run,member.memberId(),member.sourceItemId(),member.observationId(),HexFormat.of().parseHex(hash),part,Timestamp.from(f.clock.instant().minusSeconds(2)),body,java.security.MessageDigest.getInstance("SHA-256").digest(CanonicalJson.bytes(payload)));
            }
            String hash=RunManifestService.partHash(run,part,members);manifest.add(new RunManifestService.Part(run,part,hash,members));
            var body=JsonMapper.shared().createObjectNode().put("run_id",run).put("part_no",part).put("part_hash",hash);var array=body.putArray("members");for(var member:members)array.addObject().put("member_id",member.memberId()).put("source_item_id",member.sourceItemId()).put("observation_id",member.observationId()).put("payload_hash",member.payloadHash()).putNull("replaces_observation_id");
            var action=JsonMapper.shared().createObjectNode().put("operation","manifest");action.set("business",body);
            f.db.jdbc.update("INSERT INTO request_receipts(id,run_id,scope,receipt_kind,control_operation,ack_json,business_hash) VALUES(?,?,?,'collector_control','manifest',?,?)","fictional-manifest-"+part,run,"manifest:"+run+":"+part,JdbcFrozenInputs.canonical(body),HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.CONTROL_BUSINESS,action)));
        }
        String hash=RunManifestService.manifestHash(run,count,manifest);var result=JsonMapper.shared().createObjectNode().put("run_id",run).put("manifest_hash",hash).put("part_count",manifest.size()).put("expected_count",count).put("fetch_outcome","succeeded");result.putObject("coverage");
        if(preRegistered)f.db.jdbc.update("UPDATE source_runs SET manifest_hash=?,delivery_state=?,fetch_state='succeeded',expected_count=?,accepted_count=?,excluded_count=0,pending_count=0,revision=7,job_refs_json='[]',part_count=?,result_json=?,finished_at=?,analysis_state='waiting_input',coverage_json='{}' WHERE id=?",HexFormat.of().parseHex(hash),count==0?"empty_success":"complete",count,count,manifest.size(),JdbcFrozenInputs.canonical(result),Timestamp.from(f.clock.instant().minusSeconds(1)),run);
        else f.db.jdbc.update("INSERT INTO source_runs(id,source_id,run_kind,business_date,generation,activation_stage,config_version,config_snapshot_json,manifest_hash,delivery_state,fetch_state,expected_count,accepted_count,excluded_count,pending_count,revision,job_refs_json,part_count,result_json,finished_at,analysis_state,coverage_json) VALUES(?,'fictional-source','daily','2026-01-01',1,'production',1,'{\"parser_version\":\"fictional-parser-v1\"}',?,?,'succeeded',?,?,0,0,7,'[]',?,?,?,'waiting_input','{}')",run,HexFormat.of().parseHex(hash),count==0?"empty_success":"complete",count,count,manifest.size(),JdbcFrozenInputs.canonical(result),Timestamp.from(f.clock.instant().minusSeconds(1)));
        var collector=new CollectionHandoffService(new JdbcCollectionHandoff(f.db.source),new CollectionIntentService(f.db.source,f.clock)::ensure);f.p1.tx(()->{collector.terminal(run);return true;});f.p1.collection=f.p1.tx(()->f.p1.collections.read(run));
        for(int n=0;n<Math.max(1,(count+99)/100);n++)assertEquals(SourceAnalysisParentStepService.Status.PREPARED,f.step.step(f.candidate(f.p1.collection.intentId())).status());
        ref=f.reference(f.p1.collection.intentId());plan=f.read(()->f.plans.readImmutable(ref));assertEquals((count+99)/100,plan.chunks().size());
    }
    SourceStageResolutionService.Snapshot snapshot(FrozenSourceStagePlan.Reference reference){
        var results=new JdbcSourceDedupResults(f.db.source);var dependency=new SourceDedupDependencyService(new JdbcSourceStageAvailability(f.db.source));
        var provider=new SourceStageResolutionService(new JdbcSourceStageRoots(f.db.source),f.plans,f.p1.universes,f.parts,new JdbcFrozenInputs(f.db.source),results,new SourceDedupResultProofService(results,f.plans,f.p1.universes),new SourceValueResultProofService(results,f.plans,f.p1.universes,dependency),dependency);
        return f.read(()->provider.read(reference));
    }
    void terminal(int part,String state){f.db.jdbc.update("UPDATE jobs SET state=?,call_count=?,revision=revision+1 WHERE id=?",state,state.equals("failed")?3:0,plan.chunks().get(part).rootId());}
    void applied(int part){
        var chunk=plan.chunks().get(part);String attempt=JdbcFrozenInputs.stableId("attempt",chunk.rootId()+":1");
        var context=FrozenApplicationContext.fromJson(CanonicalJson.parse(chunk.snapshotJson()).get(FrozenApplicationContext.FIELD));var input=context.input(chunk.rootId(),attempt,chunk.inputHash(),0);var bindings=FrozenMemberBindings.read(CanonicalJson.parse(chunk.snapshotJson()),input);
        var payload=JsonMapper.shared().createObjectNode().put("kind","dedup").put("summary","Fictional resolved summary "+part);var items=payload.putArray("items");for(String id:chunk.itemIds())items.addObject().put("item_id",id).put("category","news_tech").put("decision","new").putNull("candidate_entity_id").put("reason","Fictional original evidence").put("confidence","1").put("suggested_title","Fictional new title").putNull("suggested_summary").put("reanalyze_recommended",false);
        String envelope=ResultApplicationService.providerEnvelope(input,JdbcFrozenInputs.canonical(payload));var validated=ResultApplicationService.validate(input,envelope);var universe=f.read(()->f.p1.universes.readImmutable(plan.reference()));
        var handoff=JsonMapper.shared().createObjectNode().put("schema_version","source-member-stage-v1").put("root_id",chunk.rootId()).put("input_hash",chunk.inputHash()).put("result_hash",validated.resultHash()).put("stage","news_dedup").put("generation",plan.generation()).put("stage_part",chunk.stagePart());handoff.set("payload",payload);var facts=handoff.putArray("members");
        for(String id:chunk.itemIds()){var member=universe.members().stream().filter(m->id.equals(m.itemId())).findFirst().orElseThrow();facts.add(JsonMapper.shared().valueToTree(new SourceMemberStageService.Fact(id,universe.runId(),universe.sourceId(),member.sourceItemId(),"news_tech","fictional-entity-"+id,"ready",bindings.get(id).expectedRevision()+2,plan.scope().businessDate(),null,plan.generation(),"production")));}
        f.p1.tx(()->{f.db.jdbc.update("UPDATE jobs SET state='running' WHERE id=?",attempt);f.db.jdbc.update("UPDATE jobs SET state='running',call_count=1 WHERE id=?",chunk.rootId());var store=new JdbcResultState(f.db.source);store.retain(store.root(chunk.rootId()),attempt,validated.resultHash(),envelope,f.clock.instant());new JdbcMemberStageResults(f.db.source).save(chunk.rootId(),handoff,f.clock.instant());f.db.jdbc.update("UPDATE jobs SET state='succeeded',application_state='APPLIED',applied_at=?,revision=revision+1 WHERE id=?",Timestamp.from(f.clock.instant()),chunk.rootId());return true;});
    }
    @Test void sparseOriginalSuccessRetainsAllThreeResolutionsAndEveryOriginalDecision() throws Exception {
        setup(201);applied(0);terminal(1,"failed");applied(2);var capture=snapshot(ref);int before=f.db.count("1=1");var result=SourceDedupStageResult.createResolved(capture);var json=CanonicalJson.parse(result.json());
        assertEquals("source-dedup-stage-result-v2",json.get("schema_version").asText());assertEquals("partial",json.get("coverage_state").asText());assertEquals(3,json.get("stage_part_count").asInt());assertEquals(201,json.get("eligible_count").asInt());assertEquals(101,json.get("applied_member_count").asInt());assertEquals(100,json.get("unapplied_member_count").asInt());assertEquals(201,json.get("decisions").size());assertEquals(3,json.get("resolutions").size());assertEquals(2,json.get("fragments").size());assertEquals(0,json.get("fragments").get(0).get("stage_part").asInt());assertEquals(2,json.get("fragments").get(1).get("stage_part").asInt());assertEquals(101,result.members().size());
        for(int n=0;n<3;n++){var row=json.get("resolutions").get(n);assertEquals(10,row.size());assertEquals(plan.chunks().get(n).rootId(),row.get("root_id").asText());assertEquals(JsonMapper.shared().valueToTree(plan.chunks().get(n).itemIds()),row.get("item_ids"));}
        assertEquals("failed",json.get("resolutions").get(1).get("state").asText());assertEquals(3,json.get("resolutions").get(1).get("call_count").asInt());assertTrue(json.get("resolutions").get(1).get("applied_at").isNull());assertEquals(CanonicalJson.hash(CanonicalJson.Domain.CONTENT,json),result.hash());assertEquals(result.json(),SourceDedupStageResult.createResolved(capture).json());assertEquals(result.hash(),CanonicalJson.parse(result.observationKeyJson()).get("result_hash").asText());assertEquals(before,f.db.count("1=1"));assertThrows(UnsupportedOperationException.class,()->result.members().clear());assertFalse(result.toString().contains("Fictional resolved"));
    }
    @Test void everyExplicitTerminalFailureIsVisibleAndAllFailedDoesNotPretendZeroInput() throws Exception {
        setup(1);for(String state:List.of("failed","cancelled","discarded","superseded")){terminal(0,state);var result=SourceDedupStageResult.createResolved(snapshot(ref));var json=CanonicalJson.parse(result.json());assertEquals(1,json.get("eligible_count").asInt());assertEquals(1,json.get("unapplied_member_count").asInt());assertEquals(0,json.get("applied_member_count").asInt());assertEquals(state,json.get("resolutions").get(0).get("state").asText());assertTrue(json.get("fragments").isEmpty());assertTrue(result.members().isEmpty());assertEquals(1,result.decisions().size());}
    }
    @Test void allAppliedAndZeroDelegateToTheExactOriginalV1Bytes() throws Exception {
        for(int count:List.of(1,0)){setup(count);if(count>0)applied(0);var capture=snapshot(ref);var proofs=capture.fragments().stream().map(r->r.dedup().proof()).toList();var original=SourceDedupStageResult.create(capture.plan(),capture.universe(),capture.reference(),proofs);var resolved=SourceDedupStageResult.createResolved(capture);assertEquals(original.json(),resolved.json());assertEquals(original.hash(),resolved.hash());assertEquals(original.observationKeyJson(),resolved.observationKeyJson());assertEquals("source-dedup-stage-result-v1",CanonicalJson.parse(resolved.json()).get("schema_version").asText());}
    }
    @Test void pendingUnknownConfigurationMissingAndCapacityCannotGrantPartialCompletion() throws Exception {
        setup(1);for(String state:List.of("prepared","unknown_external","blocked_configuration","waiting_retry")){f.db.jdbc.update("UPDATE jobs SET state=? WHERE id=?",state,plan.chunks().getFirst().rootId());var capture=snapshot(ref);int count=f.db.count("1=1");assertThrows(RuntimeException.class,()->SourceDedupStageResult.createResolved(capture));assertEquals(count,f.db.count("1=1"));}
        terminal(0,"failed");var settled=snapshot(ref);int count=f.db.count("1=1");assertThrows(GenerationFlowService.CapacityGuarded.class,()->SourceDedupStageResult.createResolvedWithinCapacity(settled,64));for(int size:List.of(0,SourceDedupStageResult.MAX_BYTES+1))assertThrows(RuntimeException.class,()->SourceDedupStageResult.createResolvedWithinCapacity(settled,size));assertEquals(count,f.db.count("1=1"));
        f.db.jdbc.update("DELETE FROM jobs WHERE id=?",plan.chunks().getFirst().rootId());var missing=snapshot(ref);assertThrows(RuntimeException.class,()->SourceDedupStageResult.createResolved(missing));assertThrows(RuntimeException.class,()->SourceDedupStageResult.createResolved(null));
    }
    @Test void completedP2SnapshotCannotBecomeADedupResult() throws Exception {
        f=new SourceAnalysisParentStepTest(){@Override GenerationJdbcStoreTest database(){return SourceDedupResolvedStageResultTest.this.database();}};f.completedP1();f.step.step(f.candidate(f.handoffs.ref.ownerId()));var child=f.candidate(f.handoffs.read().p2Owner());f.step.step(child);var reference=f.reference(child.id());f.completeP2(reference);var capture=snapshot(reference);assertTrue(capture.settled());assertThrows(RuntimeException.class,()->SourceDedupStageResult.createResolved(capture));
    }
}
