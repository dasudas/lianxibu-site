package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import tools.jackson.databind.node.ObjectNode;

/** Actual report body application, permanent ready receipt and period intents; original report input facts and model result are fictional. */
class ReportCompletionReceiptTest {
    ReportResultDownstreamTest h;JdbcReportCompletionReceipts rows;
    ResultJdbcStoreTest fixture(){return new ResultJdbcStoreTest();}
    @BeforeEach void setup(){h=new ReportResultDownstreamTest(){@Override ResultJdbcStoreTest fixture(){return ReportCompletionReceiptTest.this.fixture();}};h.setup();rows=new JdbcReportCompletionReceipts(h.f.source);}
    <T>T rr(java.util.function.Supplier<T> work){var tx=new TransactionTemplate(h.f.tx);tx.setReadOnly(true);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);return tx.execute(s->work.get());}
    JdbcReportCompletionReceipts.Ready read(){return rr(()->rows.read(ResultJdbcStoreTest.ROOT));}
    void apply(){assertEquals(ResultApplicationService.Status.APPLIED,h.f.apply().status());}
    void change(java.util.function.Consumer<ObjectNode> mutation){var body=(ObjectNode)CanonicalJson.parse(h.f.jdbc.queryForObject("SELECT action_payload FROM jobs WHERE target_type='report_result_ready'",String.class));mutation.accept(body);String raw=JdbcFrozenInputs.canonical(body);h.f.jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=? WHERE target_type='report_result_ready'",raw,JdbcGenerationLineage.hash(raw));}
    @Test void ActualAppliedReportKeepsFullNativeIdentityVersionAndOriginalTimeAcrossReplay(){assertNull(read());apply();var ready=read();assertEquals(ResultJdbcStoreTest.ROOT,ready.rootId());assertEquals("daily_report",ready.stage());assertEquals(1,ready.generation());assertEquals(h.f.store.input().inputHash(),ready.inputHash());assertEquals(2,ready.intentIds().size());assertEquals("report",ready.version().contentKind());assertEquals(Instant.parse("2025-12-31T16:00:00Z"),ready.periodStart());assertEquals(Instant.parse("2026-01-01T16:00:00Z"),ready.periodEnd());assertEquals(h.f.clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS),ready.recordedAt());assertFalse(ready.toString().contains("fictional"));assertThrows(UnsupportedOperationException.class,()->ready.intentIds().clear());h.f.apply();assertEquals(ready,read());assertEquals(1,h.ready());}
    @Test void DamagedNativeRolesAndAliasesCannotGrantCompletion(){for(String field:List.of("revision=1","state='failed'","operator_id='fictional-other'","root_job_id='fictional-alien-root'","id='"+"d".repeat(64)+"'")){setup();apply();h.f.jdbc.update("UPDATE jobs SET "+field+" WHERE target_type='report_result_ready'");assertThrows(RuntimeException.class,this::read,field);}setup();apply();var original=read();for(String blocked:List.of("record_kind='logical_task'","call_count=1")){assertThrows(RuntimeException.class,()->h.f.jdbc.update("UPDATE jobs SET "+blocked+" WHERE target_type='report_result_ready'"));assertEquals(original,read());}setup();apply();h.f.jdbc.update("UPDATE jobs SET created_at=? WHERE target_type='report_result_ready'",LocalDateTime.ofInstant(h.f.clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS).plusSeconds(1),ZoneOffset.UTC));assertThrows(RuntimeException.class,this::read);}
    @Test void RehashedWrongRootVersionPeriodAndPeriodIntentOrExtraFieldsRemainInvalid(){for(String field:List.of("root","version","period","intent","extra")){setup();apply();change(body->{switch(field){case "root"->body.put("root_id","fictional-other-root");case "version"->((ObjectNode)body.get("content_version")).put("input_job_id","fictional-alien-root");case "period"->body.put("period_end","2026-01-02T16:00:00.000000Z");case "intent"->body.putArray("intent_ids").add("fictional-forged-intent");default->body.put("fictional_extra",true);}});assertThrows(RuntimeException.class,this::read,field);}}
    @Test void UtcMicrosecondNativeReceiptReadsTheSameTimeInFourJvmZones(){apply();var value=read();var original=TimeZone.getDefault();try{for(String zone:List.of("UTC","Asia/Shanghai","Asia/Tokyo","America/Los_Angeles")){TimeZone.setDefault(TimeZone.getTimeZone(zone));assertEquals(value,read(),zone);}}finally{TimeZone.setDefault(original);}}
    @Test void PhysicalPayloadCapacityExplicitRrAndColdStoreAreMandatory(){apply();change(body->body.put("fictional_oversize","F".repeat(20000)));assertThrows(RuntimeException.class,this::read);assertThrows(RuntimeException.class,()->rows.read(ResultJdbcStoreTest.ROOT));var tx=new TransactionTemplate(h.f.tx);tx.setReadOnly(true);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);assertThrows(RuntimeException.class,()->tx.execute(s->rows.read(ResultJdbcStoreTest.ROOT)));assertThrows(RuntimeException.class,()->h.f.writes.execute(h.f.action("fictional-wrong-ready-tx"),key->null,c->rows.read(ResultJdbcStoreTest.ROOT)));var source=mock(javax.sql.DataSource.class);new JdbcReportCompletionReceipts(source);verifyNoInteractions(source);}
}
