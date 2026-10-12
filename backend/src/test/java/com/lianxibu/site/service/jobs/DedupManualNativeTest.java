package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.config.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.jobs.JobsService.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Independent original two-member dedup consumer; the separate 201-member partial gate is unchanged. */
class DedupManualNativeTest {
    SourceDedupResolvedStageResultTest base;GenerationJdbcStoreTest db;ResultJdbcStoreTest.FaultManager tx;
    WorkerAuthorizationService.Identity identity;ResultApplicationFactory factory;String hash;int sends;
    final Actor owner=new Actor("fictional-owner","owner");
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    void columns(String table,String... definitions) throws Exception {
        var names=new HashSet<String>();try(var c=db.source.getConnection();var r=c.getMetaData().getColumns(c.getCatalog(),null,"%",null)){while(r.next())if(table.equalsIgnoreCase(r.getString("TABLE_NAME")))names.add(r.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));}
        for(String d:definitions)if(!names.contains(d.split(" ")[0]))db.jdbc.execute("ALTER TABLE "+table+" ADD "+d);
    }
    void confirmed() throws Exception {
        base=new SourceDedupResolvedStageResultTest(){@Override GenerationJdbcStoreTest database(){return DedupManualNativeTest.this.database();}};base.setup(2);db=base.f.db;WorkerCandidateSelectionScopeTest.installWorker(db);
        columns("jobs","report_mode VARCHAR(32)","validity_revision BIGINT","reverses_disposition_id VARCHAR(64)","error_code VARCHAR(64)");
        columns("analyzed_entities","created_at TIMESTAMP(6)","last_seen_at TIMESTAMP(6)","last_occurrence_at TIMESTAMP(6)","occurrence_count BIGINT","repeat_count BIGINT","analysis_generation BIGINT","aliases_json TEXT");
        columns("source_run_items","counted_occurrence_key BINARY(32) UNIQUE","validity_revision BIGINT DEFAULT 0","is_new INT","dedup_json TEXT","analysis_job_id VARCHAR(64)","applied_at TIMESTAMP(6)");
        if(db.source instanceof org.h2.jdbcx.JdbcDataSource){db.jdbc.execute("CREATE ALIAS IF NOT EXISTS JSON_EXTRACT FOR 'com.lianxibu.site.service.jobs.TransportUnknownServiceTest.extract'");db.jdbc.execute("CREATE ALIAS IF NOT EXISTS JSON_UNQUOTE FOR 'com.lianxibu.site.service.jobs.TransportUnknownServiceTest.unquote'");}
        tx=new ResultJdbcStoreTest.FaultManager(db.source);final DispatchReadinessService[] readiness={null};new DispatchSourcePlanConfigurationTest().context(db).run(c->{assertNull(c.getStartupFailure());readiness[0]=c.getBean(DispatchReadinessService.class);});
        var worker=new WorkerAuthorizationService(db.writes,new WorkerJdbcStore(new JdbcWorkerState(db.source),new JdbcJobRecords(db.source),"fictional-dedup-manual-worker",()->false,readiness[0],base.f.clock,tx,ignored->true),base.f.clock,n->0,()->true);
        var claim=worker.claim(new SharedWriteExecutor.ActionContext("fictional-dedup-manual-claim",base.plan.chunks().getFirst().rootId(),null));assertEquals(WorkerAuthorizationService.State.CLAIMED,claim.state());identity=claim.permit().identity();assertEquals(WorkerAuthorizationService.State.SENT,worker.dispatch(claim.permit(),request->{sends++;return "fictional transport only";}).state());
        var graph=new SourceAnalysisProofGraph(db.source,tx,base.f.clock,()->false);factory=new ResultApplicationFactory(db.source,tx,base.f.clock,graph.dependencies(),graph.dedupPermits());var input=factory.forAttempt(identity).input();
        if(db.source instanceof org.h2.jdbcx.JdbcDataSource){var cutoff=WireValues.utcMicroseconds(CanonicalJson.parse(new JdbcFrozenInputs(db.source).read(identity.rootId(),identity.inputHash())).get("cutoff"));db.jdbc.update(connection->{var statement=connection.prepareStatement("UPDATE jobs SET input_cutoff_at=? WHERE id=?");statement.setTimestamp(1,java.sql.Timestamp.from(cutoff),java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")));statement.setString(2,identity.rootId());return statement;});}
        var payload=JsonMapper.shared().createObjectNode().put("kind","dedup").put("summary","Fictional original dedup review");var items=payload.putArray("items");for(String id:base.plan.chunks().getFirst().itemIds())items.addObject().put("item_id",id).put("category","news_tech").put("decision","new").putNull("candidate_entity_id").put("reason","Fictional source evidence").put("confidence","1").put("suggested_title","Fictional native title").putNull("suggested_summary").put("reanalyze_recommended",false);
        var envelope=CanonicalJson.parse(ResultApplicationService.providerEnvelope(input,JdbcFrozenInputs.canonical(payload)));hash=envelope.get("result_hash").textValue();new TransportUnknownService(db.writes,new JdbcTransportUnknown(db.source),base.f.clock,"fictional-dedup-manual-worker").release(identity,TransportUnknownService.Cause.TRANSPORT_UNKNOWN,new SharedWriteExecutor.ActionContext("fictional-dedup-manual-unknown",identity.rootId(),identity.inputHash()));
        var command=new JobAction(revision(),"resolve_external","Fictional original dedup confirmation",identity.attemptId(),null,null,"succeeded",List.of("fictional-evidence"),new ValidatedResult("1",identity.inputHash(),payload,hash),null);assertTrue(new OwnerJobResolveExternalService(db.source,tx,db.writes,base.f.clock).resolve(owner,identity.rootId(),command,"fictional-dedup-manual-confirm").rootResultRetained());assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM analyzed_entities",Integer.class));
    }
    long revision(){return new JdbcResultState(db.source).root(identity.rootId()).revision();}
    OwnerJobApplyResultService.Applied apply(String key,long before){return new OwnerJobApplyResultService(new JdbcOwnerRetainedResultReads(db.source),tx,factory::forManual).apply(owner,identity.rootId(),before,(Long)null,hash,"Fictional separate dedup application",key);}
    @Test void OriginalDedupMembersEntityContributionAndDownstreamCommitOnce() throws Exception {
        confirmed();long before=revision();var slot=new JdbcWorkerState(db.source).slot();var result=apply("fictional-dedup-native-apply",before);assertEquals(before+1,result.revision());assertNull(result.contentId());assertNull(result.contentRevision());assertEquals(2,db.jdbc.queryForObject("SELECT COUNT(*) FROM source_run_items WHERE entity_id IS NOT NULL AND dedup_json IS NOT NULL AND analysis_job_id=?",Integer.class,identity.rootId()));assertEquals(2L,db.jdbc.queryForObject("SELECT SUM(occurrence_count) FROM analyzed_entities",Long.class));assertNotNull(base.f.read(()->new JdbcSourceDedupResults(db.source).handoff(identity.rootId())));assertEquals(result,apply("fictional-dedup-native-apply",before));assertEquals(2L,db.jdbc.queryForObject("SELECT SUM(occurrence_count) FROM analyzed_entities",Long.class));assertEquals(slot,new JdbcWorkerState(db.source).slot());assertEquals(1,sends);
    }
    @Test void ChangedLastOriginalMemberRollsBackEarlierEntityAndEveryReceipt() throws Exception {
        confirmed();long before=revision();db.jdbc.update("UPDATE source_run_items SET revision=revision+1 WHERE id=?",base.plan.chunks().getFirst().itemIds().getLast());assertThrows(RuntimeException.class,()->apply("fictional-dedup-native-stale",before));assertEquals(before,revision());assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM analyzed_entities",Integer.class));assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM source_run_items WHERE entity_id IS NOT NULL",Integer.class));assertEquals(0,db.count("target_type='result_application'"));assertEquals(1,sends);
    }
}
