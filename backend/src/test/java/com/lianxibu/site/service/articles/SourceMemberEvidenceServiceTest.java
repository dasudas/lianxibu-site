package com.lianxibu.site.service.articles;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.articles.JdbcSourceMemberEvidence;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.*;

public class SourceMemberEvidenceServiceTest {
 public JdbcDataSource source;public JdbcTemplate jdbc;public TransactionTemplate tx;public SourceMemberEvidenceService service;
 public final Instant cutoff=Instant.parse("2026-01-02T00:00:00Z");
 public static final String PAYLOAD="""
 {"title":"Fictional source title","source_url":"https://example.invalid/story","published_at":null,"updated_at":null,
 "time_basis":"unknown","source_summary":"Fictional summary","body_text":"Fictional body","content_status":"full","body_truncated":false,
 "parser_version":"fictional-parser-v1","config_version":1,"source_order":{"basis":"unknown","domain":null,"value":null,"evidence_ref":null}}
 """;
 @BeforeEach public void setup() throws Exception {
  source=new JdbcDataSource();source.setURL("jdbc:h2:mem:member-evidence-"+System.nanoTime()+";DB_CLOSE_DELAY=-1;MODE=MySQL");jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
  jdbc.execute("CREATE TABLE source_runs(id VARCHAR(64) PRIMARY KEY,source_id VARCHAR(80),activation_stage VARCHAR(16),delivery_state VARCHAR(24),finished_at TIMESTAMP,config_version BIGINT,config_snapshot_json CLOB)");
  jdbc.execute("CREATE TABLE source_run_items(id VARCHAR(64) PRIMARY KEY,run_id VARCHAR(64),source_id VARCHAR(80),revision BIGINT,category VARCHAR(16),created_at TIMESTAMP,terminal_state VARCHAR(16),state VARCHAR(32),projection_decision VARCHAR(16),effective_valid INT,evidence_json CLOB,evidence_hash BINARY(32))");
  jdbc.update("INSERT INTO source_runs VALUES('fictional-run','fictional-source','production','complete',?,1,?)",Timestamp.from(cutoff.minusSeconds(1)),"{\"parser_version\":\"fictional-parser-v1\"}");
  var canonical=CanonicalJson.bytes(CanonicalJson.parse(PAYLOAD));var hash=java.security.MessageDigest.getInstance("SHA-256").digest(canonical);
  jdbc.update("INSERT INTO source_run_items VALUES('fictional-item','fictional-run','fictional-source',2,'news_tech',?,'accepted','pending','initialized',1,?,?)",Timestamp.from(cutoff.minusSeconds(2)),PAYLOAD,hash);
  service=new SourceMemberEvidenceService(new JdbcSourceMemberEvidence(source));
 }
 @Test void originalHashedFactsSupplyIdentityAndRevision(){var values=tx.execute(s->service.read(Set.of("fictional-item"),cutoff));var fact=values.getFirst();assertEquals(2,fact.revision());assertEquals("Fictional source title",fact.title());assertTrue(fact.identityKey().contains("https://example.invalid/story"));assertEquals(CanonicalJson.parse(PAYLOAD),CanonicalJson.parse(fact.payloadJson()));}
 @Test void changedHashIncompleteScopeAndMissingTransactionAreRejected(){assertThrows(IllegalStateException.class,()->service.read(Set.of("fictional-item"),cutoff));jdbc.update("UPDATE source_run_items SET evidence_json='{}'");assertThrows(RuntimeException.class,()->tx.execute(s->service.read(Set.of("fictional-item"),cutoff)));assertThrows(RuntimeException.class,()->tx.execute(s->service.read(Set.of("fictional-missing"),cutoff)));}
 @Test void futureNonProductionAndCrossSourceAreRejected(){jdbc.update("UPDATE source_runs SET finished_at=?",Timestamp.from(cutoff.plusSeconds(1)));assertThrows(RuntimeException.class,()->tx.execute(s->service.read(Set.of("fictional-item"),cutoff)));jdbc.update("UPDATE source_runs SET finished_at=?,activation_stage='trial'",Timestamp.from(cutoff));assertThrows(RuntimeException.class,()->tx.execute(s->service.read(Set.of("fictional-item"),cutoff)));jdbc.update("UPDATE source_runs SET activation_stage='production',source_id='fictional-other'");assertThrows(RuntimeException.class,()->tx.execute(s->service.read(Set.of("fictional-item"),cutoff)));}
}
