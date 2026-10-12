package com.lianxibu.site.service.sources;
import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDate;
import org.junit.jupiter.api.*;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import com.lianxibu.site.repository.sources.JdbcSourceRunReads;
import com.lianxibu.site.service.identity.IdentityService;

class SourceRunReadServiceTest {
    JdbcTemplate jdbc; SourceRunReadService service;
    final IdentityService.Actor owner=new IdentityService.Actor("fictional-owner","owner");
    javax.sql.DataSource source() {
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:run-reads-"+System.nanoTime()+";MODE=MySQL;DB_CLOSE_DELAY=-1");return ds;
    }
    void ddl(String statement) {
        if(jdbc.getDataSource() instanceof org.springframework.jdbc.datasource.DriverManagerDataSource)
            statement=statement.replace("coverage_json VARCHAR(4000)","coverage_json JSON").replace("result_json VARCHAR(4000)","result_json JSON").replace("TIMESTAMP","DATETIME(6)");
        jdbc.execute(statement);
    }
    @BeforeEach void setup() {
        var ds=source();
        jdbc=new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS source_run_items");jdbc.execute("DROP TABLE IF EXISTS source_runs");
        ddl("CREATE TABLE source_runs(id VARCHAR(64) PRIMARY KEY,source_id VARCHAR(80),run_kind VARCHAR(32),business_date DATE,"
            +"generation BIGINT,window_start TIMESTAMP,window_end TIMESTAMP,fetch_mode VARCHAR(16),collection_reason VARCHAR(32),"
            +"config_version BIGINT,revision BIGINT,delivery_state VARCHAR(32),fetch_state VARCHAR(32),analysis_state VARCHAR(32),"
            +"coverage_json VARCHAR(4000),expected_count BIGINT,accepted_count BIGINT,excluded_count BIGINT,pending_count BIGINT,"
            +"barrier_closed_at TIMESTAMP,result_json VARCHAR(4000),manifest_hash BINARY(32))");
        ddl("CREATE TABLE source_run_items(id VARCHAR(64) PRIMARY KEY,run_id VARCHAR(64),member_id VARCHAR(64),source_item_id VARCHAR(1000),"
            +"category VARCHAR(16),entity_id VARCHAR(64),revision BIGINT,terminal_state VARCHAR(16),state VARCHAR(32),is_new INT,high_value INT,"
            +"projection_decision VARCHAR(16),exclusion_reason VARCHAR(128),applied_at TIMESTAMP)");
        for(int n=1;n<=3;n++)jdbc.update("INSERT INTO source_runs VALUES(?,?,'daily',?,1,'2026-01-01 00:00:00','2026-01-02 00:00:00',"
            +"'daily_delta','daily',1,2,'pending','pending','waiting_input','{}',NULL,0,0,0,NULL,NULL,NULL)",
            "fictional-run-"+n,n==3?"fictional-other":"fictional-source",java.sql.Date.valueOf("2026-01-0"+n));
        jdbc.update("INSERT INTO source_run_items VALUES('fictional-item','fictional-run-1','fictional-member',NULL,NULL,NULL,0,'pending','pending',NULL,NULL,NULL,NULL,NULL)");
        service=new SourceRunReadService(new JdbcSourceRunReads(ds),new DataSourceTransactionManager(ds));
    }
    @Test void filteredStablePagesAndUnknownsAreNotFakedSuccess() {
        var all=service.listRuns(owner,null,null,1,2);assertEquals(3,all.total());assertEquals("fictional-run-3",all.items().getFirst().runId());
        assertEquals(2,service.listRuns(owner,"fictional-source",null,1,50).total());
        assertEquals(1,service.listRuns(owner,null,LocalDate.of(2026,1,1),1,50).total());
        assertTrue(service.listRuns(owner,null,null,99,50).items().isEmpty());
        var run=service.run(owner,"fictional-run-1");assertEquals("no_input",run.coverage().state());assertNull(run.expectedCount());assertFalse(run.lateResultsAvailable());
        var item=service.listItems(owner,"fictional-run-1",1,50).items().getFirst();assertNull(item.highValue());assertNull(item.category());
        assertThrows(IdentityService.Rejected.class,()->service.run(owner,"fictional-missing"));
        assertThrows(IdentityService.Rejected.class,()->service.listItems(owner,"fictional-missing",1,50));
        assertThrows(IdentityService.Rejected.class,()->service.listRuns(new IdentityService.Actor("fictional-user","user"),null,null,1,50));
    }
    @Test void lateResultUsesActualApplicationTimeAndMalformedCoverageFailsClosed() {
        jdbc.update("UPDATE source_runs SET barrier_closed_at='2026-01-02 00:00:00' WHERE id='fictional-run-1'");
        jdbc.update("UPDATE source_run_items SET applied_at='2026-01-02 00:00:00'");assertFalse(service.run(owner,"fictional-run-1").lateResultsAvailable());
        jdbc.update("UPDATE source_run_items SET applied_at='2026-01-02 00:00:01'");assertTrue(service.run(owner,"fictional-run-1").lateResultsAvailable());
        jdbc.update("UPDATE source_runs SET coverage_json='{\"state\":\"complete\",\"unexpected\":\"private\"}' WHERE id='fictional-run-1'");
        assertThrows(IdentityService.Rejected.class,()->service.run(owner,"fictional-run-1"));
        jdbc.update("UPDATE source_run_items SET exclusion_reason='private body with spaces'");
        assertThrows(IdentityService.Rejected.class,()->service.listItems(owner,"fictional-run-1",1,50));
    }
    @Test void realCoverageHashesAndAllProjectionDecisionsKeepTheirContractMeaning() {
        String coverage="{\"state\":\"partial\",\"missing_inputs\":[{\"kind\":\"source\",\"id\":\"fictional-missing\",\"business_date\":null,\"reason\":\"unavailable\"}],\"missing_dates\":[\"2026-01-01\"],\"no_update_dates\":[]}";
        jdbc.update("UPDATE source_runs SET coverage_json=?,manifest_hash=?,result_json='{}'",coverage,new byte[32]);
        var run=service.run(owner,"fictional-run-1");assertEquals("partial",run.coverage().state());assertEquals(1,run.coverage().missingInputs().size());assertEquals("0".repeat(64),run.manifestHash());assertNotNull(run.resultBusinessHash());
        for(String decision:java.util.List.of("initialized","unchanged","promoted","historical","needs_review")){
            jdbc.update("UPDATE source_run_items SET projection_decision=?,high_value=0,is_new=1",decision);
            var item=service.listItems(owner,"fictional-run-1",1,50).items().getFirst();assertEquals(decision,item.projectionDecision());assertTrue(item.isNew());assertFalse(item.highValue());
        }
    }
}
