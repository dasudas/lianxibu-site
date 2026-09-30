package com.lianxibu.site.service.jobs;
import java.time.*;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import com.lianxibu.site.repository.jobs.*;
final class OperationalPersistenceFixture {
    final JdbcDataSource source=new JdbcDataSource();
    final JdbcTemplate jdbc;
    final DataSourceTransactionManager tx;
    final MutableClock clock=new MutableClock();
    final SharedWriteExecutor writes;
    OperationalPersistenceFixture() {
        source.setURL("jdbc:h2:mem:ops-jdbc-"+System.nanoTime()+";DB_CLOSE_DELAY=-1;MODE=MySQL");
        jdbc=new JdbcTemplate(source);tx=new DataSourceTransactionManager(source);
        jdbc.execute("CREATE TABLE worker_lock(id INT PRIMARY KEY)");jdbc.update("INSERT INTO worker_lock VALUES(1)");
        jdbc.execute("CREATE TABLE runtime_settings(setting_key VARCHAR(64) PRIMARY KEY,value_json CLOB NOT NULL,revision BIGINT NOT NULL,updated_at TIMESTAMP NOT NULL)");
        jdbc.execute("CREATE TABLE jobs(id VARCHAR(64) PRIMARY KEY,business_key VARCHAR(191) NOT NULL UNIQUE,"
                + "task_kind VARCHAR(32) NOT NULL,target_id VARCHAR(64) NOT NULL,state VARCHAR(32) NOT NULL,"
                + "priority_no INT NOT NULL,eligible_at TIMESTAMP NOT NULL,revision BIGINT NOT NULL,"
                + "created_at TIMESTAMP NOT NULL,updated_at TIMESTAMP NOT NULL,record_kind VARCHAR(24) NOT NULL,"
                + "record_identity_hash BINARY(32) NOT NULL UNIQUE,call_count INT NOT NULL,"
                + "max_attempts INT,logical_key CLOB,logical_key_hash BINARY(32),generation BIGINT,"
                + "stage_part BIGINT,intent_id VARCHAR(64),task_type VARCHAR(32),business_date DATE,"
                + "input_state VARCHAR(16),root_job_id VARCHAR(64),attempt_no INT,request_json CLOB,"
                + "snapshot_owner_id VARCHAR(64),input_hash BINARY(32),owner_job_id VARCHAR(64),"
                + "purpose VARCHAR(16),stamp_id VARCHAR(64),part_no BIGINT,payload_json CLOB,"
                + "payload_hash BINARY(32),action_key_hash BINARY(32),target_type VARCHAR(32),"
                + "target_key_json CLOB,target_key_hash BINARY(32),operator_id VARCHAR(64),"
                + "reason VARCHAR(2048),acted_at TIMESTAMP,action_payload CLOB,"
                + "action_payload_hash BINARY(32),applied_at TIMESTAMP,provider_request_id VARCHAR(256),"
                + "usage_json CLOB,cost_decimal DECIMAL(20,8),"
                + "UNIQUE(root_job_id,attempt_no),UNIQUE(owner_job_id,purpose,stamp_id,part_no),"
                + "CHECK ((record_kind='logical_task' AND max_attempts=3 AND call_count<=3"
                + " AND priority_no BETWEEN 1 AND 14) OR (record_kind<>'logical_task'"
                + " AND max_attempts IS NULL AND call_count=0 AND priority_no=0)),"
                + "CHECK (record_kind<>'ai_attempt' OR (root_job_id IS NOT NULL AND attempt_no BETWEEN 1 AND 3"
                + " AND request_json IS NOT NULL AND snapshot_owner_id IS NOT NULL AND input_hash IS NOT NULL)),"
                + "CHECK (record_kind<>'snapshot_part' OR (owner_job_id IS NOT NULL"
                + " AND purpose IS NOT NULL AND stamp_id IS NOT NULL AND part_no IS NOT NULL"
                + " AND payload_json IS NOT NULL AND payload_hash IS NOT NULL)),"
                + "CHECK (record_kind<>'disposition' OR (action_key_hash IS NOT NULL"
                + " AND target_type IS NOT NULL AND target_key_json IS NOT NULL"
                + " AND target_key_hash IS NOT NULL AND operator_id IS NOT NULL"
                + " AND reason IS NOT NULL AND acted_at IS NOT NULL AND action_payload IS NOT NULL"
                + " AND action_payload_hash IS NOT NULL)))");
        writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(source),tx);
    }
    OperationalJdbcStore store() {return new OperationalJdbcStore(new JdbcOperationalSummaries(source),new JdbcJobRecords(source),clock);}
    OperationalMonitoringService monitor(OperationalJdbcStore store) {return new OperationalMonitoringService(store,
            ()->new OperationalMonitoringService.Configuration(1,MonitoringFixtures.complete()),writes,clock);}
    static final class MutableClock extends Clock {
        Instant now=Instant.parse("2026-10-01T00:00:00Z");
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return now;}
    }
}
