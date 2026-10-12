package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual metadata SQL only, fictional native prepared jobs; no model request. */
@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class OwnerJobViewsMySqlTest {
    GenerationJdbcStoreMySqlTest f;JdbcOwnerJobViews store;TransactionTemplate read,writer;
    @BeforeEach void setup(){
        f=new GenerationJdbcStoreMySqlTest();f.fixture();f.flow(f.store).prepare(f.command(false),f.action());
        for(String field:List.of("report_mode VARCHAR(16)","application_state VARCHAR(32)","slot_released_at DATETIME(6)","release_reason VARCHAR(32)"))f.jdbc.execute("ALTER TABLE jobs ADD "+field);
        for(String field:List.of("slot_state VARCHAR(16)","slot_revision BIGINT","job_id VARCHAR(64)"))f.jdbc.execute("ALTER TABLE worker_lock ADD "+field);f.jdbc.update("UPDATE worker_lock SET slot_state='free',slot_revision=4");
        f.jdbc.execute("ALTER TABLE jobs MODIFY input_cutoff_at DATETIME(6)");f.jdbc.execute("ALTER TABLE jobs MODIFY eligible_at DATETIME(6) NOT NULL");
        var manager=new DataSourceTransactionManager(f.source);read=new TransactionTemplate(manager);read.setReadOnly(true);read.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);writer=new TransactionTemplate(manager);store=new JdbcOwnerJobViews(f.source);
    }
    <T>T read(java.util.function.Supplier<T> work){return read.execute(s->work.get());}
    @Test void actualNativeRootIntentAttemptAndSlotAreSeparateSafeMetadata(){
        var root=read(()->store.job(GenerationJdbcStoreTest.OWNER));assertNotNull(root);assertEquals("logical_task",root.kind());assertEquals(3,root.maxAttempts());assertEquals(0,root.callCount());assertEquals("ready",root.inputState());assertNotNull(read(()->store.intentRevision(root.intentId())));
        var attempts=read(()->store.attempts(root.id()));assertEquals(1,attempts.size());assertEquals(root.id(),attempts.getFirst().rootId());assertEquals(root.id(),attempts.getFirst().snapshotOwnerId());assertEquals(1,attempts.getFirst().attemptNo());assertNull(attempts.getFirst().maxAttempts());assertEquals("free",read(()->store.slot()).state());assertEquals(4,read(()->store.slot()).revision());
        assertFalse(root.toString().contains("fictional body"));assertFalse(Arrays.stream(JdbcOwnerJobViews.Row.class.getRecordComponents()).anyMatch(c->Set.of("requestJson","inputSnapshotJson","payloadJson","resultJson","actionPayload","targetKeyJson").contains(c.getName())));
    }
    @Test void internalDayDirectoryPartsAndControlReceiptsDoNotEnterCountOrPages(){
        long before=read(()->store.page(null,null,1,50)).total();writer.execute(s->{var records=new JdbcJobRecords(f.source);records.ensureIntent(new JdbcJobRecords.Intent("fictional-day-owner","fictional-day-key",1,"source_day_roster",LocalDate.of(2026,1,1)),f.clock.instant());records.putPart(new JdbcJobRecords.Part("fictional-day-part","fictional-day-owner","input","fictional-day-stamp",0,"{}","a".repeat(64)),f.clock.instant());records.appendDisposition(new JdbcJobRecords.Disposition("fictional-day-seal","fictional-day-seal-key","source_day_cohort","{}","fictional-actor","Fictional day metadata","{}"),f.clock.instant());return true;});
        var page=read(()->store.page(null,null,1,50));assertEquals(before,page.total());assertFalse(page.items().stream().anyMatch(r->r.id().startsWith("fictional-day")));assertNull(read(()->store.job("fictional-day-owner")));assertNull(read(()->store.job("fictional-day-part")));assertNull(read(()->store.job("fictional-day-seal")));
        var filtered=read(()->store.page("disposition","generation_prepare",1,50));assertEquals(1,filtered.total());assertEquals(1,filtered.items().size());assertEquals("generation_prepare",filtered.items().getFirst().targetType());assertTrue(read(()->store.page(null,null,1000,1)).items().isEmpty());assertEquals(before,read(()->store.page(null,null,1000,1)).total());
    }
    @Test void onlyActualReadonlyTransactionAndExactIdentifiersMayRead(){
        assertThrows(RuntimeException.class,()->store.job(GenerationJdbcStoreTest.OWNER));assertThrows(RuntimeException.class,()->writer.execute(s->store.page(null,null,1,10)));assertNull(read(()->store.job(GenerationJdbcStoreTest.OWNER.toUpperCase(Locale.ROOT))));
        for(long page:List.of(0L,Long.MAX_VALUE))assertThrows(RuntimeException.class,()->read(()->store.page(null,null,page,50)));assertThrows(RuntimeException.class,()->read(()->store.page(null,null,1,51)));assertThrows(RuntimeException.class,()->read(()->store.page("fictional-unknown",null,1,1)));assertThrows(RuntimeException.class,()->read(()->store.job("fictional-unsafe\n")));
    }
    @Test void oversizedUsageAndMissingMetadataOrLostSqlAreNeverAnEmptyPage(){
        String large="[\""+"F".repeat(270000)+"\"]";f.jdbc.update("UPDATE jobs SET missing_inputs_json=? WHERE id=?",large,GenerationJdbcStoreTest.OWNER);assertThrows(RuntimeException.class,()->read(()->store.job(GenerationJdbcStoreTest.OWNER)));assertThrows(RuntimeException.class,()->read(()->store.page("logical_task",null,1,10)));
        f.jdbc.update("UPDATE jobs SET missing_inputs_json='[]',usage_json=? WHERE record_kind='ai_attempt'","{\"fictional\":\""+"F".repeat(17000)+"\"}");assertThrows(RuntimeException.class,()->read(()->store.attempts(GenerationJdbcStoreTest.OWNER)));f.jdbc.execute("DROP TABLE worker_lock");assertThrows(RuntimeException.class,()->read(()->store.slot()));f.jdbc.execute("DROP TABLE jobs");assertThrows(RuntimeException.class,()->read(()->store.page(null,null,1,1)));
    }
    @Test void nativeUtcMicrosecondsDoNotDependOnJvmDefaultTimezone(){
        Instant expected=Instant.parse("2026-01-02T01:23:45.123456Z");TimeZone before=TimeZone.getDefault();try{TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));f.jdbc.update(connection->{var statement=connection.prepareStatement("UPDATE jobs SET input_cutoff_at=?,eligible_at=? WHERE id=?");var utc=Calendar.getInstance(TimeZone.getTimeZone("UTC"));statement.setTimestamp(1,java.sql.Timestamp.from(expected),utc);statement.setTimestamp(2,java.sql.Timestamp.from(expected),utc);statement.setString(3,GenerationJdbcStoreTest.OWNER);return statement;});var row=read(()->store.job(GenerationJdbcStoreTest.OWNER));assertEquals(expected,row.cutoff());assertEquals(expected,row.eligibleAt());}finally{TimeZone.setDefault(before);}
    }
}
