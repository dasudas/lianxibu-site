package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.jobs.JdbcWorkerState;
import java.time.*;
import java.sql.Timestamp;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Selected physical header only; no readiness, reservation or send permission. */
class WorkerCandidatePointTest {
    WorkerCandidatePageTest f;
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    void setup(int count){f=new WorkerCandidatePageTest(){@Override GenerationJdbcStoreTest database(){return WorkerCandidatePointTest.this.database();}};f.setup(count);}
    <T>T writer(java.util.function.Supplier<T> work){return new TransactionTemplate(new DataSourceTransactionManager(f.f.source)).execute(s->work.get());}
    JdbcWorkerState.Prepared point(String root,String attempt){return writer(()->f.service.candidate(root,attempt,f.now));}
    @Test void EveryActual103OriginalCandidateMatchesTheOriginalHeaderAndChangedNativeFactsAreRead(){setup(103);var original=f.read(()->f.service.candidates(f.now));for(var row:original)assertEquals(row,point(row.root(),row.attempt()));assertEquals(0,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));f.f.jdbc.update("UPDATE jobs SET revision=revision+1,state='waiting_retry' WHERE id='root-fictional-page-000'");var changed=point("root-fictional-page-000","attempt-fictional-page-000");assertEquals(1,changed.revision());assertEquals("waiting_retry",changed.rootState());f.f.jdbc.update("UPDATE jobs SET eligible_at=? WHERE id='root-fictional-page-000'",Timestamp.from(f.now.plusSeconds(1)));assertNull(point("root-fictional-page-000","attempt-fictional-page-000"));f.f.jdbc.update("UPDATE jobs SET eligible_at=?,call_count=1 WHERE id='root-fictional-page-000'",Timestamp.from(f.now));assertNull(point("root-fictional-page-000","attempt-fictional-page-000"));assertEquals(0,f.f.count("target_type IN ('worker_claim','worker_send')"));}
    @Test void MissingWrongAttemptAliasTransactionsAndParametersCannotGrantACandidate(){setup(2);assertNull(point("fictional-missing","fictional-missing-attempt"));assertNull(point("root-fictional-page-000","attempt-fictional-page-001"));assertThrows(RuntimeException.class,()->f.service.candidate("root-fictional-page-000","attempt-fictional-page-000",f.now));assertThrows(RuntimeException.class,()->f.read(()->f.service.candidate("root-fictional-page-000","attempt-fictional-page-000",f.now)));assertThrows(RuntimeException.class,()->writer(()->f.service.candidate("root-fictional-page-000","attempt-fictional-page-000",null)));assertThrows(RuntimeException.class,()->point("x".repeat(65),"attempt-fictional-page-000"));if(f.f.source instanceof org.h2.jdbcx.JdbcDataSource)assertNull(point("ROOT-FICTIONAL-PAGE-000","attempt-fictional-page-000"));else assertThrows(RuntimeException.class,()->point("ROOT-FICTIONAL-PAGE-000","attempt-fictional-page-000"));assertEquals(0,f.f.count("target_type IN ('worker_claim','worker_send')"));}
    @Test void OversizeRequestReturnsNullWithoutManufacturingOrTruncatingIt(){setup(1);String original="\""+"x".repeat(1048577)+"\"";f.f.jdbc.update("UPDATE jobs SET request_json=? WHERE id='attempt-fictional-page-000'",original);assertNull(point("root-fictional-page-000","attempt-fictional-page-000").request());assertEquals(original,f.f.jdbc.queryForObject("SELECT request_json FROM jobs WHERE id='attempt-fictional-page-000'",String.class));assertEquals(0,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));}
}
