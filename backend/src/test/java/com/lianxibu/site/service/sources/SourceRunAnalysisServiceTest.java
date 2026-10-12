package com.lianxibu.site.service.sources;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.sources.SourceRunAnalysisCommand;
import com.lianxibu.site.domain.sources.SourceRunAnalysisCommand.Position;
import com.lianxibu.site.repository.sources.JdbcSourceRunAnalysis;
import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

class SourceRunAnalysisServiceTest {
    DataSource source;JdbcTemplate jdbc;SharedWriteExecutor writes;SourceRunAnalysisService service;int actions;
    Map<String,Position> proofs=new HashMap<>();static final Instant CUTOFF=Instant.parse("2026-01-02T00:02:00.123456Z");
    DataSource source(){return new DriverManagerDataSource("jdbc:h2:mem:source-analysis-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");}
    @BeforeEach void setup(){source=source();jdbc=new JdbcTemplate(source);boolean h2=source instanceof DriverManagerDataSource d&&d.getUrl().startsWith("jdbc:h2:");for(String table:List.of("source_runs","sources","worker_lock"))jdbc.execute("DROP TABLE IF EXISTS "+table);String json=h2?"CLOB":"JSON",text=h2?"CLOB":"MEDIUMTEXT";
        jdbc.execute("CREATE TABLE worker_lock(id INT PRIMARY KEY)");jdbc.update("INSERT INTO worker_lock VALUES(1)");jdbc.execute("CREATE TABLE sources(id VARCHAR(80) PRIMARY KEY,category VARCHAR(16))");jdbc.update("INSERT INTO sources VALUES('fictional-source','news_tech')");
        jdbc.execute("CREATE TABLE source_runs(id VARCHAR(64) PRIMARY KEY,source_id VARCHAR(80),run_kind VARCHAR(32),business_date DATE,generation BIGINT,manifest_hash BINARY(32),expected_count BIGINT,accepted_count BIGINT,excluded_count BIGINT,pending_count BIGINT,activation_stage VARCHAR(16),delivery_state VARCHAR(32),analysis_state VARCHAR(32),revision BIGINT,job_refs_json "+json+",dedup_summary "+text+",analysis_summary "+text+",result_json "+json+",coverage_json "+json+")");
        jdbc.update("INSERT INTO source_runs VALUES('fictional-run','fictional-source','daily',?,7,?,5,3,2,0,'production','complete','waiting_input',2,?,NULL,NULL,?,?)",java.sql.Date.valueOf("2026-01-01"),HexFormat.of().parseHex("a".repeat(64)),"[\"fictional-original-root\"]","{\"fictional_collector\":true}","{}");
        writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(source),new DataSourceTransactionManager(source));service=new SourceRunAnalysisService(new JdbcSourceRunAnalysis(source),(run,refs)->refs.stream().map(proofs::get).filter(Objects::nonNull).max(Comparator.comparing(Position::inputCutoff).thenComparingLong(Position::generation)).orElse(null));
    }
    SourceRunAnalysisCommand command(String key,long gen,Instant cutoff,String receipt){var p=new Position("fictional-owner-"+receipt,key,gen,cutoff,"b".repeat(64),receipt);proofs.put(receipt,p);return new SourceRunAnalysisCommand("fictional-run","fictional-source",LocalDate.parse("2026-01-01"),7,"a".repeat(64),3,2,p,"Fictional full dedup tail", "Fictional full value tail "+receipt,"complete");}
    SourceRunAnalysisCommand command(){return command("fictional-value-key",1,CUTOFF,"fictional-final-1");}
    SourceRunAnalysisService.Outcome apply(SourceRunAnalysisCommand command){return writes.execute(new SharedWriteExecutor.ActionContext("fictional-projection-"+actions++,"fictional-owner",null),k->null,a->service.apply(command));}
    String text(String column){return jdbc.queryForObject("SELECT "+column+" FROM source_runs",String.class);}
    long revision(){return jdbc.queryForObject("SELECT revision FROM source_runs",Long.class);}
    @Test void completeAndPartialProjectExactlyOnceAndPreserveCollectorProof(){
        var c=command();assertEquals("applied",apply(c).disposition());assertEquals(3,revision());assertEquals(c.analysisSummary(),text("analysis_summary"));assertEquals(c.dedupSummary(),text("dedup_summary"));assertEquals("succeeded",text("analysis_state"));assertEquals("unchanged",apply(c).disposition());assertEquals(3,revision());assertTrue(text("job_refs_json").contains("fictional-original-root"));assertTrue(text("job_refs_json").contains(c.position().receiptId()));assertEquals("{\"fictional_collector\":true}",canonical(text("result_json")));assertEquals("{}",canonical(text("coverage_json")));
        var later=command("fictional-value-key",2,CUTOFF.plusSeconds(1),"fictional-final-2");var partial=new SourceRunAnalysisCommand(later.runId(),later.sourceId(),later.businessDate(),later.collectionGeneration(),later.manifestHash(),later.accepted(),later.excluded(),later.position(),later.dedupSummary(),later.analysisSummary(),"partial");assertEquals("applied",apply(partial).disposition());assertEquals("partial",text("analysis_state"));
    }
    @Test void cutoffAndSameKeyGenerationOrderProtectNewerOutputAcrossLogicalKeys(){
        var old=command();apply(old);var next=command("fictional-value-key",2,CUTOFF,"fictional-final-2");apply(next);assertEquals("superseded",apply(old).disposition());assertEquals(next.analysisSummary(),text("analysis_summary"));var different=command("fictional-other-key",1,CUTOFF.plusSeconds(1),"fictional-final-3");apply(different);assertEquals("superseded",apply(next).disposition());assertEquals(different.analysisSummary(),text("analysis_summary"));assertEquals(5,revision());assertThrows(RuntimeException.class,()->apply(command("fictional-conflicting-key",3,different.position().inputCutoff(),"fictional-final-4")));assertEquals(5,revision());
    }
    @Test void exactOriginalRunManifestCountsProductionAndReferencesAreRequired(){
        var c=command();jdbc.update("UPDATE source_runs SET manifest_hash=?",HexFormat.of().parseHex("c".repeat(64)));assertThrows(RuntimeException.class,()->apply(c));jdbc.update("UPDATE source_runs SET manifest_hash=?",HexFormat.of().parseHex("a".repeat(64)));for(String mutation:List.of("accepted_count=4","activation_stage='trial'","run_kind='probe'","generation=8","source_id='fictional-other'")){jdbc.update("UPDATE source_runs SET "+mutation);assertThrows(RuntimeException.class,()->apply(c));jdbc.update("UPDATE source_runs SET accepted_count=3,activation_stage='production',run_kind='daily',generation=7,source_id='fictional-source'");}jdbc.update("UPDATE source_runs SET job_refs_json=?","{}");assertThrows(RuntimeException.class,()->apply(c));assertNull(text("analysis_summary"));
    }
    @Test void outerFailureAndReadonlyCannotLeavePartialSummariesOrLinks(){
        var c=command();assertThrows(RuntimeException.class,()->writes.execute(new SharedWriteExecutor.ActionContext("fictional-seal-failure","fictional-owner",null),k->null,a->{service.apply(c);throw new IllegalStateException("fictional final receipt failure");}));assertEquals(2,revision());assertNull(text("analysis_summary"));assertFalse(text("job_refs_json").contains(c.position().receiptId()));assertThrows(RuntimeException.class,()->service.apply(c));var ro=new TransactionTemplate(new DataSourceTransactionManager(source));ro.setReadOnly(true);assertThrows(RuntimeException.class,()->ro.execute(s->service.apply(c)));
    }
    @Test void aliasOrUnprovenExistingProjectionAndChangedReplayAreRejected(){
        var c=command();var alias=new SourceRunAnalysisCommand("FICTIONAL-run",c.sourceId(),c.businessDate(),7,c.manifestHash(),3,2,c.position(),c.dedupSummary(),c.analysisSummary(),c.coverageState());assertThrows(RuntimeException.class,()->apply(alias));jdbc.update("UPDATE source_runs SET analysis_summary='Fictional unproven'");assertThrows(RuntimeException.class,()->apply(c));jdbc.update("UPDATE source_runs SET analysis_summary=NULL");apply(c);jdbc.update("UPDATE source_runs SET analysis_summary='Fictional changed'");assertThrows(RuntimeException.class,()->apply(c));assertEquals(3,revision());
    }
    static String canonical(String raw){return new String(com.lianxibu.site.domain.protocol.CanonicalJson.bytes(com.lianxibu.site.domain.protocol.CanonicalJson.parse(raw)),java.nio.charset.StandardCharsets.UTF_8);}
}
