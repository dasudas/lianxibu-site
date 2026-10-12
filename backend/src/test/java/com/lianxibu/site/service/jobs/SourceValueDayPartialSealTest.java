package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Native partial authority and original SQL, with the separately qualified day provider doubled. */
class SourceValueDayPartialSealTest {
    SourceValueDayResolutionTest f;SourceValuePartialArchiveService archives;SourceValuePartialSealService seals;JdbcSourceValuePartialReceipts receipts;JdbcSourceStageRoots roots;JdbcSourceValuePartialBlocks blocks;SourceValuePartialCommitPermitService permits;
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    void setup(boolean mixed)throws Exception{
        f=new SourceValueDayResolutionTest(){@Override GenerationJdbcStoreTest database(){return SourceValueDayPartialSealTest.this.database();}};f.setup(mixed);
        for(int n=0;n<f.f.plan.chunks().size();n++){f.prepare(n);f.outcomes.complete(n);}
        var tx=new DataSourceTransactionManager(f.db.source);blocks=new JdbcSourceValuePartialBlocks(f.db.source);roots=new JdbcSourceStageRoots(f.db.source);receipts=new JdbcSourceValuePartialReceipts(f.db.source);archives=new SourceValuePartialArchiveService(f.service,blocks,f.db.writes,tx,f.f.f.clock);seals=sealProvider(f.authority.service);permits=permitProvider(f.authority.service);
    }
    SourceValuePartialSealService sealProvider(SourceDedupPartialDependencyPermitService p1){return new SourceValuePartialSealService(f.service,archives,receipts,roots,f.db.writes,new DataSourceTransactionManager(f.db.source),f.f.f.clock,p1);}
    SourceValuePartialCommitPermitService permitProvider(SourceDedupPartialDependencyPermitService p1){return new SourceValuePartialCommitPermitService(seals,f.service,blocks,receipts,roots,p1,new DataSourceTransactionManager(f.db.source));}
    SourceValueStageResult verify(SourceValuePartialCommitPermitService.Captured captured){return f.original.p1.tx(()->permits.verify(captured));}
    int count(){return f.db.count("target_type='source_value_partial_result'");}
    @Test void ZeroAndAllAppliedValueRequireOriginalPartialAuthorityAndKeepDay()throws Exception{
        for(boolean mixed:new boolean[]{false,true}){
            setup(mixed);int calls=f.db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class);var sealed=seals.seal(f.ref);var captured=permits.capture(f.ref);var result=verify(captured);var body=CanonicalJson.parse(result.json());assertEquals(sealed.proof().json(),result.json());assertEquals("source-value-stage-result-v2",body.get("schema_version").asText());assertEquals(1,body.get("missing").size());assertEquals("dedup_failed",body.get("missing").get(0).get("reason").asText());assertEquals(CanonicalJson.parse(f.f.plan.scope().stageContextJson()),body.get("stage_context"));assertFalse(body.get("stage_context").get(FrozenSourceDayDedupDependency.FIELD).get("original_batch_eligible").asBoolean());assertEquals(mixed?200:0,body.get("applied_member_count").asInt());assertEquals(1,count());assertEquals(calls,f.db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
            int rows=f.db.count("1=1");f.db.jdbc.execute("DROP TABLE source_run_items");f.db.jdbc.execute("DROP TABLE analyzed_entities");assertEquals(result.json(),verify(permits.capture(f.ref)).json());assertEquals(rows,f.db.count("1=1"));
        }
    }
    @Test void MissingActualP1PermitCannotSealOrCaptureEvenAllValuePartsApplied()throws Exception{
        setup(true);assertNotNull(archives.stage(f.ref));assertThrows(RuntimeException.class,()->sealProvider(null).seal(f.ref));assertEquals(0,count());assertNotNull(seals.seal(f.ref));assertThrows(RuntimeException.class,()->permitProvider(null).capture(f.ref));assertEquals(1,count());
    }
    @Test void WriterRechecksActualP1PermitAfterCaptureForSealAndDownstreamCommit()throws Exception{
        setup(true);var changed=spy(f.authority.service);doThrow(new IllegalStateException("fictional original partial native changed")).when(changed).verify(any(),any());assertThrows(RuntimeException.class,()->sealProvider(changed).seal(f.ref));assertEquals(0,count());assertNotNull(seals.seal(f.ref));permits=permitProvider(changed);var captured=permits.capture(f.ref);assertThrows(RuntimeException.class,()->verify(captured));permits=permitProvider(f.authority.service);var original=permits.capture(f.ref);String p1=f.authority.f.plan.chunks().getLast().rootId();f.db.jdbc.update("UPDATE jobs SET revision=revision+1 WHERE id=?",p1);assertThrows(RuntimeException.class,()->verify(original));assertEquals(1,count());
    }
}
