package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Actual native report preparation and result application; original INPUT/model genesis is fictional. */
class ReportTaskHeaderTest {
    ReportResultDownstreamTest h;JdbcReportTaskHeaders rows;
    ResultJdbcStoreTest fixture(){return new ResultJdbcStoreTest();}
    @BeforeEach void setup(){h=new ReportResultDownstreamTest(){@Override ResultJdbcStoreTest fixture(){return ReportTaskHeaderTest.this.fixture();}};h.setup();rows=new JdbcReportTaskHeaders(h.f.source);}
    <T>T rr(java.util.function.Supplier<T> work){var tx=new TransactionTemplate(h.f.tx);tx.setReadOnly(true);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);return tx.execute(s->work.get());}
    JdbcReportTaskHeaders.Header read(){return rr(()->rows.read(ResultJdbcStoreTest.ROOT));}
    @Test void NativePreparedAndAppliedHeadersKeepExactOriginalIdentityAndBudget(){var before=read();assertEquals("daily_report",before.stage());assertEquals("running",before.state());assertEquals(1,before.calls());assertEquals(1,before.generation());assertEquals(LocalDate.parse("2026-01-01"),before.day());assertEquals(h.f.store.input().inputHash(),before.inputHash());assertNull(before.resultHash());assertNull(rr(()->rows.read("fictional-absent")));assertEquals(ResultApplicationService.Status.APPLIED,h.f.apply().status());var after=read();assertEquals("succeeded",after.state());assertEquals("APPLIED",after.application());assertNotNull(after.appliedAt());assertEquals(before.createdAt(),after.createdAt());assertEquals(before.logicalKey(),after.logicalKey());assertFalse(after.toString().contains("fictional"));}
    @Test void RoleKeyIdentityStageAndUnusedFieldsCannotBeSubstituted(){for(String field:List.of("logical_key_hash=UNHEX('"+"0".repeat(64)+"')","record_identity_hash=UNHEX('"+"0".repeat(64)+"')","task_type='agent_analysis'","stage_part=1","priority_no=12","target_id='fictional-alien'","task_kind='disposition'","intent_id='fictional-alien'","root_job_id='fictional-other-root'","purpose='input'","snapshot_owner_id='fictional-other-root'")){setup();h.f.jdbc.update("UPDATE jobs SET "+field+" WHERE id=?",ResultJdbcStoreTest.ROOT);assertThrows(RuntimeException.class,this::read,field);}}
    @Test void HeaderCapsOriginalManifestsBeforeAnyFullBodyIsRead(){for(String field:List.of("logical_key","input_snapshot_json","result_json")){setup();int size=field.equals("logical_key")?4097:field.equals("input_snapshot_json")?65537:16385;String raw=field.equals("logical_key")?"F".repeat(size):"{\"fictional\":\""+"F".repeat(size)+"\"}";h.f.jdbc.update("UPDATE jobs SET "+field+"=? WHERE id=?",raw,ResultJdbcStoreTest.ROOT);assertThrows(RuntimeException.class,this::read,field);}}
    @Test void NativeUtcFactsDoNotChangeWithFourJvmDefaults(){assertEquals(ResultApplicationService.Status.APPLIED,h.f.apply().status());var value=read();var original=TimeZone.getDefault();try{for(String zone:List.of("UTC","Asia/Shanghai","Asia/Tokyo","America/Los_Angeles")){TimeZone.setDefault(TimeZone.getTimeZone(zone));assertEquals(value,read(),zone);}}finally{TimeZone.setDefault(original);}}
    @Test void DateAndTimestampDamageRejectsRatherThanInventingAvailability(){h.f.jdbc.update("UPDATE jobs SET updated_at=? WHERE id=?",LocalDateTime.ofInstant(h.f.clock.instant().minusSeconds(1),ZoneOffset.UTC),ResultJdbcStoreTest.ROOT);assertThrows(RuntimeException.class,this::read);setup();h.f.jdbc.update("UPDATE jobs SET business_date=NULL WHERE id=?",ResultJdbcStoreTest.ROOT);assertThrows(RuntimeException.class,this::read);}
    @Test void ColdConstructionAndExplicitReadonlyRrAreRequired(){var source=mock(javax.sql.DataSource.class);new JdbcReportTaskHeaders(source);verifyNoInteractions(source);assertThrows(RuntimeException.class,()->rows.read(ResultJdbcStoreTest.ROOT));var tx=new TransactionTemplate(h.f.tx);tx.setReadOnly(true);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);assertThrows(RuntimeException.class,()->tx.execute(s->rows.read(ResultJdbcStoreTest.ROOT)));assertThrows(RuntimeException.class,()->h.f.writes.execute(h.f.action("fictional-header-writer"),key->null,c->rows.read(ResultJdbcStoreTest.ROOT)));}
}
