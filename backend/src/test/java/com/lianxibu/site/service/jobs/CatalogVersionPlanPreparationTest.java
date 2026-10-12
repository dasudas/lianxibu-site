package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.repository.sources.*;
import com.lianxibu.site.repository.articles.*;
import com.lianxibu.site.service.articles.*;
import com.lianxibu.site.service.sources.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;

/** Native original analysis plus complete retained fictional collector manifest, actual handoff, Universe, version plan and part preparation. */
class CatalogVersionPlanPreparationTest extends CatalogVersionQualificationTest {
    static final String RUN="fictional-version-source-run";
    CatalogSourcePlanPreparationService preparation;CollectionHandoffProofService collections;FrozenSourceUniverseService universes;FrozenSourceStagePlanService plans;
    CollectionHandoffProofService.Proof collection;int actions;
    @Override @BeforeEach void setup(){source("agent");}
    String sourceRunId(){return RUN;}
    void beforeCurrentCollectorRun(){}
    void source(String category){source(category,false);}
    void source(String category,boolean unchanged){
        initialize(category,false);if(unchanged){current=f.catalogPayload();update();}actions=0;String json=mysql()?"JSON":"CLOB";
        f.jdbc.execute("DROP TABLE IF EXISTS sources");f.jdbc.execute("CREATE TABLE sources(id VARCHAR(80) PRIMARY KEY,category VARCHAR(16))");f.jdbc.update("INSERT INTO sources VALUES('fictional-source',?)",category);
        for(String column:List.of("title VARCHAR(512)","summary TEXT","first_recorded_at TIMESTAMP(6)"))f.jdbc.execute("ALTER TABLE analyzed_entities ADD "+column);
        f.jdbc.update("UPDATE analyzed_entities SET title='Fictional previous catalog',summary='Fictional previous summary',first_recorded_at=?",java.sql.Timestamp.from(f.clock.instant().minusSeconds(3)));
        for(String column:List.of("run_kind VARCHAR(32)","generation BIGINT","manifest_hash BINARY(32)","fetch_state VARCHAR(32)","expected_count BIGINT","accepted_count BIGINT","excluded_count BIGINT","pending_count BIGINT","revision BIGINT","job_refs_json "+json,"part_count BIGINT","result_json "+json))f.jdbc.execute("ALTER TABLE source_runs ADD "+column);
        for(String column:List.of("member_id VARCHAR(64)","observation_id VARCHAR(64)","payload_hash BINARY(32)","replaces_observation_id VARCHAR(64)","part_no BIGINT","target_business_date DATE"))f.jdbc.execute("ALTER TABLE source_run_items ADD "+column);
        f.jdbc.execute("DROP TABLE IF EXISTS request_receipts");f.jdbc.execute("CREATE TABLE request_receipts(id VARCHAR(64) PRIMARY KEY,run_id VARCHAR(64),scope VARCHAR(128),receipt_kind VARCHAR(32),control_operation VARCHAR(32),ack_json "+json+",business_hash BINARY(32))");
        beforeCurrentCollectorRun();
        String bodyHash=CatalogPreviousAnalysisTest.Fixture.sha(CanonicalJson.bytes(current));
        var member=new RunManifestService.Member("fictional-version-member","fictional-current-source-item","fictional-version-observation",bodyHash,null);
        String partHash=RunManifestService.partHash(sourceRunId(),0,List.of(member));var part=new RunManifestService.Part(sourceRunId(),0,partHash,List.of(member));String manifest=RunManifestService.manifestHash(sourceRunId(),1,List.of(part));
        var payload=JsonMapper.shared().createObjectNode().put("run_id",sourceRunId()).put("part_no",0).put("part_hash",partHash);payload.putArray("members").addObject().put("member_id",member.memberId()).put("source_item_id",member.sourceItemId()).put("observation_id",member.observationId()).put("payload_hash",member.payloadHash()).putNull("replaces_observation_id");
        var action=JsonMapper.shared().createObjectNode().put("operation","manifest");action.set("business",payload);
        f.jdbc.update("INSERT INTO request_receipts(id,run_id,scope,receipt_kind,control_operation,ack_json,business_hash) VALUES('fictional-version-part',? ,?,'collector_control','manifest',?,?)",sourceRunId(),"manifest:"+sourceRunId()+":0",JdbcFrozenInputs.canonical(payload),HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.CONTROL_BUSINESS,action)));
        var result=JsonMapper.shared().createObjectNode().put("run_id",sourceRunId()).put("manifest_hash",manifest).put("part_count",1).put("expected_count",1).put("fetch_outcome","succeeded");result.putObject("coverage");
        recordCurrentCollectorRun(manifest,JdbcFrozenInputs.canonical(result));
        f.jdbc.update("UPDATE source_run_items SET run_id=?,member_id=?,observation_id=?,payload_hash=?,part_no=0,target_business_date='2026-01-01' WHERE id='fictional-current-item'",sourceRunId(),member.memberId(),member.observationId(),HexFormat.of().parseHex(bodyHash));
        var handoff=new CollectionHandoffService(new JdbcCollectionHandoff(f.source),new CollectionIntentService(f.source,f.clock)::ensure);write(()->{handoff.terminal(sourceRunId());return true;});collections=new CollectionHandoffProofService(f.source,handoff);collection=write(()->collections.read(sourceRunId()));
        universes=new FrozenSourceUniverseService(new JdbcFrozenSourceUniverses(f.source),new SourceRunUniverseService(new JdbcSourceRunUniverse(f.source),handoff),f.clock);plans=new FrozenSourceStagePlanService(new JdbcFrozenSourcePlans(f.source),universes,f.clock,"fictional-owner");preparation=preparation();
    }
    void recordCurrentCollectorRun(String manifest,String result){
        f.jdbc.update("INSERT INTO source_runs(id,source_id,activation_stage,delivery_state,finished_at,config_version,config_snapshot_json,business_date,run_kind,generation,manifest_hash,fetch_state,expected_count,accepted_count,excluded_count,pending_count,revision,job_refs_json,part_count,result_json) VALUES(?,'fictional-source','production','complete',?,1,'{\"parser_version\":\"fictional-parser-v1\"}','2026-01-01','daily',1,?,'succeeded',1,1,0,0,0,'[]',1,?)",sourceRunId(),java.sql.Timestamp.from(f.clock.instant().minusSeconds(1)),HexFormat.of().parseHex(manifest),result);
    }
    CatalogSourcePlanPreparationService preparation(){
        var evidence=new SourceMemberEvidenceService(new JdbcSourceMemberEvidence(f.source));var eligibility=new CatalogDedupEligibilityService(new JdbcNewsDedupEligibility(f.source),evidence);var history=new CatalogCandidateHistoryService(f.source,f.category,f.clock);
        return new CatalogSourcePlanPreparationService(new JdbcFrozenSourcePlans(f.source),universes,plans,collections,eligibility,history::capture,f.category,()->new FrozenGenerationSettings(1,List.of(),List.of()),()->true,new CatalogSourceVersionPlanPermitService(f.source,f.tx,f.category));
    }
    <T>T write(java.util.function.Supplier<T> work){return f.writes.execute(new SharedWriteExecutor.ActionContext("fictional-version-plan-action-"+actions++,sourceRunId(),null),k->null,a->work.get());}
    CatalogSourcePlanPreparationService.PreparedPlan build(){var captured=write(()->preparation.capture(collection.intentId(),sourceRunId()));return preparation.build(captured);}
    SourcePartitionPlanner.Plan prepare(){var built=build();return write(()->preparation.commit(built));}
    @Test void FourCategoriesFreezeActualKnownNativeVersionProofThenPrepareOriginalPartWithoutCalling(){
        for(String category:List.of("agent","skill","workflow","model")){
            source(category);var plan=prepare();assertEquals(1,plan.chunks().size());var chunk=plan.chunks().getFirst();var snapshot=CanonicalJson.parse(chunk.snapshotJson());
            var input=FrozenApplicationContext.fromJson(snapshot.get(FrozenApplicationContext.FIELD)).input(chunk.rootId(),"fictional-attempt",chunk.inputHash(),0);assertEquals("2",input.dedupResultVersion());assertEquals(1,FrozenCatalogVersionProjections.read(snapshot.get(FrozenCatalogVersionProjections.FIELD),input).entries().size());
            var part=new SourceStagePreparationService(f.source,plans,universes,collections::read,f.clock,"fictional-owner");var frozen=write(()->part.preparePart(collection.intentId(),0,"fictional-version-prepare-part"));assertEquals(chunk.snapshotJson(),frozen.snapshotJson());assertEquals(chunk.requestJson(),frozen.requestJson());assertEquals("site-catalog-dedup-v2",CanonicalJson.parse(frozen.requestJson()).get("prompt_version").asText());assertEquals(1,f.sends);assertEquals(1,f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
        }
    }
    @Test void MissingPreviousEvidenceFreezesUnknownAndKnownFalseKeepsFullProof(){
        f.jdbc.update("UPDATE contents SET input_job_id=NULL");var plan=prepare();var snapshot=CanonicalJson.parse(plan.chunks().getFirst().snapshotJson());assertTrue(snapshot.get(FrozenCatalogVersionProjections.FIELD).get("items").isEmpty());assertTrue(snapshot.get(FrozenApplicationContext.FIELD).get("version_eligible_item_ids").isEmpty());
        source("agent",true);plan=prepare();snapshot=CanonicalJson.parse(plan.chunks().getFirst().snapshotJson());assertEquals(1,snapshot.get(FrozenCatalogVersionProjections.FIELD).get("items").size());assertFalse(snapshot.get(FrozenCatalogVersionProjections.FIELD).get("items").get(0).get("projection").get("version_changed").booleanValue());assertTrue(snapshot.get(FrozenApplicationContext.FIELD).get("version_eligible_item_ids").isEmpty());assertEquals(1,f.sends);
    }
    @Test void ChangedOriginalAnalysisProofAfterBuildRejectsPlanAndOuterFailureRollsBack(){
        var built=build();int count=f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class);f.jdbc.update("UPDATE jobs SET payload_json=? WHERE owner_job_id=? AND purpose='input'","{\"fictional_changed\":true}",CatalogPreviousAnalysisTest.Fixture.ROOT);
        assertThrows(RuntimeException.class,()->write(()->preparation.commit(built)));assertEquals(count,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class));assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='source_stage_plan'",Integer.class));assertEquals(1,f.sends);
        source("agent");var prepared=build();count=f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class);assertThrows(RuntimeException.class,()->write(()->{preparation.commit(prepared);throw new IllegalStateException("fictional downstream rollback");}));assertEquals(count,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class));assertNotNull(write(()->preparation.commit(prepared)));
    }
    @Test void RebuiltConsumerReplaysPermanentOriginalBeforeCurrentProofOrTables(){
        var original=prepare();int count=f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class);f.jdbc.update("UPDATE jobs SET payload_json=? WHERE owner_job_id=? AND purpose='input'","{\"fictional_later_changed\":true}",CatalogPreviousAnalysisTest.Fixture.ROOT);f.jdbc.execute("DROP TABLE analyzed_entities");f.jdbc.execute("DROP TABLE contents");preparation=preparation();assertEquals(original,prepare());assertEquals(count,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class));assertEquals(1,f.sends);
    }
}
