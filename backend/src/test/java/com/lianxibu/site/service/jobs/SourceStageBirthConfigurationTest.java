package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.config.NewsSourcePreparationConfiguration;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.GenerationFlowService.Stage;
import java.sql.Timestamp;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.node.ObjectNode;

class SourceStageBirthConfigurationTest {
    SourceStageBirthProofServiceTest f;
    SourceStageBirthProofServiceTest fixture(){return new SourceStageBirthProofServiceTest();}
    boolean materialView(){return true;}
    @BeforeEach void setup(){
        f=fixture();f.setup();String json=f.f.source instanceof org.h2.jdbcx.JdbcDataSource?"CLOB":"JSON";
        f.f.jdbc.execute("DROP TABLE IF EXISTS source_run_items");f.f.jdbc.execute("DROP TABLE IF EXISTS source_runs");
        f.f.jdbc.execute("CREATE TABLE source_runs(id VARCHAR(64) PRIMARY KEY,source_id VARCHAR(80),activation_stage VARCHAR(16),delivery_state VARCHAR(24),finished_at TIMESTAMP(6),config_version BIGINT,config_snapshot_json "+json+")");
        f.f.jdbc.execute("CREATE TABLE source_run_items(id VARCHAR(64) PRIMARY KEY,run_id VARCHAR(64),source_id VARCHAR(80),revision BIGINT,category VARCHAR(16),created_at TIMESTAMP(6),terminal_state VARCHAR(16),state VARCHAR(32),projection_decision VARCHAR(16),effective_valid INT,evidence_json "+json+",evidence_hash BINARY(32))");
        f.f.jdbc.update("INSERT INTO source_runs VALUES('fictional-run','fictional-source','production','complete',?,1,?)",Timestamp.from(java.time.Instant.parse("2026-01-01T20:00:00Z")),"{\"parser_version\":\"fictional-parser-v1\"}");
    }
    SourceStageBirthProofTest.Archive archive(long generation,int count){
        var raw=SourceStageBirthProofTest.archive(generation,count,true);var universe=FrozenSourceUniverseTest.universe(count,false);var ref=raw.plan().reference();
        var plan=new SourcePartitionPlanner().plan(Stage.NEWS_VALUE,raw.plan().logicalKey(),generation,universe,ref,raw.plan().decisions(),raw.plan().scope(),(ids,n,root)->{
            var node=(ObjectNode)CanonicalJson.parse(SourcePartitionPlannerTest.snapshot(ref,ids,n,root,"Fictional normalized evidence"));node.put("generation",generation);node.put("stage","news_value");var context=(ObjectNode)node.get(FrozenApplicationContext.FIELD);context.put("generation",generation);context.put("stage","news_value");
            var bindings=node.get(FrozenMemberBindings.FIELD).get("members");
            for(int i=0;i<ids.size();i++){
                String id=ids.get(i);var body=(ObjectNode)CanonicalJson.parse(com.lianxibu.site.service.articles.SourceMemberEvidenceServiceTest.PAYLOAD);body.put("source_url","https://example.invalid/"+id);body.put("title","Fictional evidence");body.putNull("source_summary");
                var item=(ObjectNode)node.get("members").get(i);item.set("body",body);item.put("body_hash",CanonicalJson.hash(CanonicalJson.Domain.CONTENT,body));
                var identity=tools.jackson.databind.json.JsonMapper.shared().createObjectNode();identity.put("namespace","article_url");identity.put("key","https://example.invalid/"+id);String key=JdbcFrozenInputs.canonical(identity);
                var binding=(ObjectNode)bindings.get(i);binding.put("identity_key",key);binding.put("new_entity_id",FrozenMemberBindings.entityId(root,id,"news_tech",key));
            }
            if(materialView())NewsValueInputProfileTest.installMaterialView(node,"fictional-source");
            return JdbcFrozenInputs.canonical(node);
        });
        var frozen=f.tx(()->{new JdbcJobRecords(f.f.source).ensureIntent(new JdbcJobRecords.Intent(ref.ownerId(),plan.logicalKey(),generation,"news_value",plan.scope().businessDate()),f.f.clock.instant());
            var original=new FrozenSourceUniverseService(new JdbcFrozenSourceUniverses(f.f.source),run->universe,f.f.clock);assertEquals(ref,original.freeze(ref.ownerId(),universe.runId()));return new FrozenSourceStagePlanService(new JdbcFrozenSourcePlans(f.f.source),original,f.f.clock,"fictional-owner").freeze(plan);});
        for(var chunk:plan.chunks())for(var item:CanonicalJson.parse(chunk.snapshotJson()).get("members")){
            String id=item.get("id").asText();if(f.f.jdbc.queryForObject("SELECT COUNT(*) FROM source_run_items WHERE id=?",Integer.class,id)==0){String body=JdbcFrozenInputs.canonical(item.get("body"));
                f.f.jdbc.update("INSERT INTO source_run_items VALUES(?,'fictional-run','fictional-source',1,'news_tech',?,'accepted','pending','initialized',1,?,?)",id,Timestamp.from(java.time.Instant.parse("2026-01-01T19:00:00Z")),body,JdbcGenerationLineage.hash(body));}
        }
        return new SourceStageBirthProofTest.Archive(plan,frozen);
    }
    SourceStagePreparationService preparation(){return new NewsSourcePreparationConfiguration().sourceStagePreparation(f.f.source);}
    DispatchReadinessService dispatch(){return configuredDispatch();}
    DispatchReadinessService configuredDispatch(){final DispatchReadinessService[] value={null};new DispatchSourcePlanConfigurationTest().context(f.f).run(context->{assertNull(context.getStartupFailure());value[0]=context.getBean(DispatchReadinessService.class);});return value[0];}
    /** Explicit isolated compatibility fixture for original request and birth invariants. */
    DispatchReadinessService legacyDispatch(){
        var inputs=new JdbcFrozenInputs(f.f.source);
        return new DispatchReadinessService(new JdbcDispatchInputs(f.f.source),inputs,new FrozenDependencyIndexService(inputs,new JdbcFrozenDependencies(f.f.source)),new GenerationLineageService(new JdbcGenerationLineage(f.f.source),f.f.clock,"analysis-worker",f.service::read),org.mockito.Mockito.mock(com.lianxibu.site.service.content.ContentReadService.class),new com.lianxibu.site.service.articles.SourceMemberEvidenceService(new com.lianxibu.site.repository.articles.JdbcSourceMemberEvidence(f.f.source)),target->true,run->{throw new AssertionError("no synthetic collection");},new SourcePartitionProofService(f.archives),true);
    }
    GenerationFlowService.Frozen prepare(SourceStageBirthProofTest.Archive current){return f.tx(()->preparation().preparePart(current.ref().ownerId(),0,"fictional-configured-born"));}
    @Test void configuredPreparationUsesActualBirthAndReadonlyRestartRecoversExactRequest(){
        archive(1,0);var current=archive(2,5);var result=prepare(current);int count=f.f.count("1=1");
        assertEquals(current.plan().chunks().getFirst().requestJson(),result.requestJson());assertEquals(result,f.readonly(()->preparation().committedPart(current.ref().ownerId(),0,"fictional-configured-born")));
        assertEquals(count,f.f.count("1=1"));assertEquals(1,f.f.count("record_kind='ai_attempt'"));assertEquals(0,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void actualConfiguredDispatchRejectsMaterialOnlyValueEvenWithValidOriginalBirth(){
        archive(1,0);var current=archive(2,5);var result=prepare(current);var readiness=configuredDispatch();int count=f.f.count("1=1");
        assertEquals(2,f.readonly(()->f.service.read(result.ownerId())).generation());assertThrows(RuntimeException.class,()->f.tx(()->readiness.ready(result.ownerId())));assertEquals(count,f.f.count("1=1"));
        assertEquals(0,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void missingOriginalPreviousArchiveRejectsConfiguredPreparationReplayAndDispatch(){
        var previous=archive(1,0);var current=archive(2,5);var result=prepare(current);int count=f.f.count("1=1");
        assertEquals(1,f.f.jdbc.update("DELETE FROM jobs WHERE owner_job_id=? AND stamp_id=? AND part_no=1",previous.ref().ownerId(),JdbcFrozenSourcePlans.stamp(previous.ref().ownerId())));
        assertThrows(RuntimeException.class,()->f.readonly(()->preparation().committedPart(current.ref().ownerId(),0,"fictional-configured-born")));
        assertThrows(RuntimeException.class,()->f.tx(()->dispatch().ready(result.ownerId())));assertEquals(count-1,f.f.count("1=1"));assertEquals(1,f.f.count("record_kind='ai_attempt'"));
    }
}
