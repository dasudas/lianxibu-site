package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.jobs.JdbcJobRecords;
import java.time.*;
import java.util.TimeZone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class JdbcIntentBusinessDateMySqlTest {
    private GenerationJdbcStoreMySqlTest fixture;
    private TransactionTemplate tx;
    private JdbcJobRecords records;
    @BeforeEach void setup() {fixture=new GenerationJdbcStoreMySqlTest();fixture.fixture();tx=new TransactionTemplate(new DataSourceTransactionManager(fixture.source));records=new JdbcJobRecords(fixture.source);}
    @ParameterizedTest @ValueSource(strings={"Asia/Tokyo","America/Los_Angeles"})
    void businessDateIsOriginalDateAcrossHostZonesAndConflictingDayIsRejected(String zone) {
        var original=TimeZone.getDefault();var day=LocalDate.of(2026,9,20);var now=Instant.parse("2026-09-20T16:10:00Z");
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(zone));
            var intent=new JdbcJobRecords.Intent("fictional-date-intent","fictional-date-key",1,"source_day_roster",day);
            assertEquals(intent.id(),tx.execute(s->records.ensureIntent(intent,now)));
            assertEquals(day.toString(),fixture.jdbc.queryForObject("SELECT business_date FROM jobs",String.class));
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));assertEquals(intent.id(),tx.execute(s->records.ensureIntent(intent,now)));
            var conflict=new JdbcJobRecords.Intent(intent.id(),intent.logicalKey(),1,intent.taskType(),day.plusDays(1));
            assertThrows(JdbcJobRecords.Conflict.class,()->tx.execute(s->records.ensureIntent(conflict,now)));
            assertEquals(1,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class));
            assertEquals(day.toString(),fixture.jdbc.queryForObject("SELECT business_date FROM jobs",String.class));
        } finally {TimeZone.setDefault(original);}
    }
}
