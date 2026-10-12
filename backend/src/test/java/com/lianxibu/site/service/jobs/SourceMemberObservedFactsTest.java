package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.articles.JdbcSourceMemberEvidence;
import com.lianxibu.site.service.articles.SourceMemberEvidenceService;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Current committed qualification only; a later writer must lock and validate again. */
class SourceMemberObservedFactsTest {
    SourceMemberObservedRowsTest f;SourceMemberEvidenceService service;
    final Instant cutoff=Instant.parse("2026-01-02T00:00:00Z");
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    void setup()throws Exception{f=new SourceMemberObservedRowsTest(){@Override GenerationJdbcStoreTest database(){return SourceMemberObservedFactsTest.this.database();}};f.setup();var payload=f.db.jdbc.queryForObject("SELECT evidence_json FROM source_run_items",String.class);f.db.jdbc.update("UPDATE source_run_items SET evidence_hash=?",java.security.MessageDigest.getInstance("SHA-256").digest(CanonicalJson.bytes(CanonicalJson.parse(payload))));service=new SourceMemberEvidenceService(new JdbcSourceMemberEvidence(f.db.source));}
    List<SourceMemberEvidenceService.Fact> read(){return f.read(()->service.readObserved(Set.of("fictional-item"),cutoff));}
    List<SourceMemberEvidenceService.Fact> writer(){return new TransactionTemplate(new DataSourceTransactionManager(f.db.source)).execute(s->service.read(Set.of("fictional-item"),cutoff));}
    @Test void ActualFactsMatchOriginalWriterAndCurrentRevisionIsReadAgain()throws Exception{setup();var values=read();assertEquals(writer(),values);assertEquals(2,values.getFirst().revision());assertEquals("Fictional source title",values.getFirst().title());assertEquals("Fictional summary",values.getFirst().summary());assertTrue(values.getFirst().identityKey().contains("https://example.invalid/story"));assertThrows(UnsupportedOperationException.class,()->values.clear());f.db.jdbc.update("UPDATE source_run_items SET revision=3");assertEquals(3,read().getFirst().revision());assertEquals(writer(),read());assertEquals(0,f.db.count("1=1"));}
    @Test void HashParserFutureSourceTrialAndReviewFailuresMatchWriter()throws Exception{for(String mutation:List.of("UPDATE source_run_items SET evidence_json='{}'","UPDATE source_runs SET config_snapshot_json='{\"parser_version\":\"fictional-different\"}'","UPDATE source_runs SET finished_at='2026-01-03 00:00:00'","UPDATE source_runs SET source_id='fictional-other'","UPDATE source_runs SET activation_stage='trial'","UPDATE source_run_items SET projection_decision='needs_review'")){setup();f.db.jdbc.update(mutation);assertThrows(RuntimeException.class,this::read);assertThrows(RuntimeException.class,this::writer);assertEquals(0,f.db.count("1=1"));}}
    @Test void RolesBoundsMissingAndNullCutoffCannotManufactureFacts()throws Exception{setup();assertThrows(RuntimeException.class,()->service.readObserved(Set.of("fictional-item"),cutoff));assertThrows(RuntimeException.class,()->new TransactionTemplate(new DataSourceTransactionManager(f.db.source)).execute(s->service.readObserved(Set.of("fictional-item"),cutoff)));assertThrows(RuntimeException.class,()->f.read(()->service.read(Set.of("fictional-item"),cutoff)));assertThrows(RuntimeException.class,()->f.read(()->service.readObserved(Set.of(),cutoff)));var tooMany=new HashSet<String>();for(int n=0;n<101;n++)tooMany.add("fictional-item-"+n);assertThrows(RuntimeException.class,()->f.read(()->service.readObserved(tooMany,cutoff)));assertThrows(RuntimeException.class,()->f.read(()->service.readObserved(Set.of("fictional-missing"),cutoff)));assertThrows(RuntimeException.class,()->f.read(()->service.readObserved(Set.of("fictional-item"),null)));assertEquals(0,f.db.count("1=1"));}
}
