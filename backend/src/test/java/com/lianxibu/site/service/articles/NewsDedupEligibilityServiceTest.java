package com.lianxibu.site.service.articles;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.sources.MemberUniverse;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.articles.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class NewsDedupEligibilityServiceTest {
    DataSource source;JdbcTemplate jdbc;TransactionTemplate tx;NewsDedupEligibilityService service;MemberUniverse.Universe universe;
    final LocalDate day=LocalDate.of(2026,1,1);final Instant cutoff=Instant.parse("2026-01-02T00:00:00Z");
    final String HASH="a".repeat(64);
    DataSource source(){var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:news-eligibility-"+System.nanoTime()+";DB_CLOSE_DELAY=-1;MODE=MySQL");return ds;}
    String jsonType(){return "CLOB";}
    @BeforeEach void setup() throws Exception {
        source=source();jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        jdbc.execute("CREATE TABLE source_runs(id VARCHAR(64) PRIMARY KEY,source_id VARCHAR(80),business_date DATE,activation_stage VARCHAR(16),delivery_state VARCHAR(24),finished_at TIMESTAMP(6),config_version BIGINT,config_snapshot_json "+jsonType()+")");
        jdbc.execute("CREATE TABLE source_run_items(id VARCHAR(64) PRIMARY KEY,run_id VARCHAR(64),source_id VARCHAR(80),source_item_id VARCHAR(4096),entity_id VARCHAR(64),target_business_date DATE,payload_hash BINARY(32),revision BIGINT,category VARCHAR(16),created_at TIMESTAMP(6),terminal_state VARCHAR(16),state VARCHAR(32),projection_decision VARCHAR(16),effective_valid INT,evidence_json "+jsonType()+",evidence_hash BINARY(32))");
        jdbc.update("INSERT INTO source_runs VALUES('fictional-run','fictional-source',?,'production','complete',?,1,?)",java.sql.Date.valueOf(day),Timestamp.from(cutoff.minusSeconds(1)),"{\"parser_version\":\"fictional-parser-v1\"}");
        var members=new ArrayList<MemberUniverse.Member>();
        for(int n=0;n<6;n++) {
            String id="fictional-item-"+n;String terminal=n==1?"excluded":"accepted";
            members.add(new MemberUniverse.Member(id,"fictional-member-"+n,"fictional-key-"+n,"fictional-observation-"+n,HASH,null,0,terminal));
            String payload=payload(n,n==5?null:Instant.parse("2026-01-01T08:00:00Z"));
            jdbc.update("INSERT INTO source_run_items VALUES(?,'fictional-run','fictional-source',?,?,NULL,?,2,'news_tech',?,?,?,'initialized',?,?,?)",id,"fictional-key-"+n,n==2?"fictional-entity":null,HexFormat.of().parseHex(HASH),Timestamp.from(cutoff.minusSeconds(2)),terminal,n==4?"needs_review":n==1?"excluded":n==2?"ready":"pending",n==3?0:1,payload,sha(payload));
        }
        universe=new MemberUniverse.Universe("fictional-run","fictional-source",HASH,List.of(new MemberUniverse.Part(0,HASH,6)),members,5,1);
        service=new NewsDedupEligibilityService(new JdbcNewsDedupEligibility(source),new SourceMemberEvidenceService(new JdbcSourceMemberEvidence(source)));
    }
    String payload(int n,Instant published) {
        var node=(tools.jackson.databind.node.ObjectNode)CanonicalJson.parse(SourceMemberEvidenceServiceTest.PAYLOAD);
        node.put("source_url","https://example.invalid/story/"+n);
        if(published==null){node.putNull("published_at");node.put("time_basis","unknown");}else{node.put("published_at",new java.time.format.DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(published));node.put("time_basis","published");}
        return new String(CanonicalJson.bytes(node),java.nio.charset.StandardCharsets.UTF_8);
    }
    byte[] sha(String payload) throws Exception{return java.security.MessageDigest.getInstance("SHA-256").digest(CanonicalJson.bytes(CanonicalJson.parse(payload)));}
    NewsDedupEligibilityService.Selection selection(){return tx.execute(a->service.read(universe,day,cutoff));}
    @Test void everyOriginalMemberHasOneDecisionAndOnlyVerifiedPendingEvidenceIsEligible() {
        var selected=selection();assertEquals(6,selected.entries().size());
        assertEquals(List.of("ELIGIBLE","COLLECTOR_EXCLUDED","ALREADY_APPLIED","NOT_AI_ELIGIBLE","NOT_AI_ELIGIBLE","NOT_AI_ELIGIBLE"),selected.entries().stream().map(e->e.reason().name()).toList());
        assertEquals("fictional-item-0",selected.entries().getFirst().fact().id());assertNull(selected.entries().get(2).fact());
        assertEquals(2,selected.entries().getFirst().fact().revision());assertEquals(6,jdbc.queryForObject("SELECT COUNT(*) FROM source_run_items",Integer.class));
        assertEquals(12,jdbc.queryForObject("SELECT SUM(revision) FROM source_run_items",Integer.class));
    }
    @Test void eligibleMetadataUsesTheActualLatestAvailabilityTimeAndExcludedRowsHaveNone() {
        var original=selection();var eligible=original.entries().getFirst();assertEquals(day,eligible.businessDate());assertEquals(cutoff.minusSeconds(1),eligible.completedAt());
        for(var entry:original.entries().subList(1,original.entries().size())){assertNull(entry.fact());assertNull(entry.businessDate());assertNull(entry.completedAt());}
        Instant received=cutoff.minusNanos(500000000);jdbc.update("UPDATE source_run_items SET created_at=? WHERE id='fictional-item-0'",Timestamp.from(received));
        assertEquals(received,selection().entries().getFirst().completedAt());
        jdbc.update("UPDATE source_run_items SET created_at=? WHERE id='fictional-item-0'",Timestamp.from(cutoff.plusSeconds(1)));
        var late=selection().entries().getFirst();assertEquals(NewsDedupEligibilityService.Reason.NOT_AI_ELIGIBLE,late.reason());assertNull(late.businessDate());assertNull(late.completedAt());
    }
    @Test void theTypedDecisionRefusesInventedMissingOrNonMicrosecondMetadata() {
        var fact=selection().entries().getFirst().fact();var eligible=NewsDedupEligibilityService.Reason.ELIGIBLE;
        assertThrows(RuntimeException.class,()->new NewsDedupEligibilityService.Entry(fact.id(),eligible,fact,null,cutoff));
        assertThrows(RuntimeException.class,()->new NewsDedupEligibilityService.Entry(fact.id(),eligible,fact,day,null));
        assertThrows(RuntimeException.class,()->new NewsDedupEligibilityService.Entry(fact.id(),eligible,fact,day,cutoff.plusNanos(1)));
        assertThrows(RuntimeException.class,()->new NewsDedupEligibilityService.Entry(fact.id(),NewsDedupEligibilityService.Reason.NOT_AI_ELIGIBLE,null,day,cutoff));
    }
    @Test void unknownStatesCrossScopeTrialAndReplacedTransferHashFailTheWholeRead() {
        for(String change:List.of("state='fictional_unknown'","source_id='fictional-other'","source_item_id='fictional-replaced'","category='agent'")) {
            jdbc.update("UPDATE source_run_items SET "+change+" WHERE id='fictional-item-0'");assertThrows(RuntimeException.class,this::selection);
            jdbc.update("UPDATE source_run_items SET state='pending',source_id='fictional-source',source_item_id='fictional-key-0',category='news_tech',payload_hash=? WHERE id='fictional-item-0'",HexFormat.of().parseHex(HASH));
        }
        jdbc.update("UPDATE source_run_items SET payload_hash=? WHERE id='fictional-item-0'",HexFormat.of().parseHex("b".repeat(64)));assertThrows(RuntimeException.class,this::selection);
        jdbc.update("UPDATE source_run_items SET payload_hash=? WHERE id='fictional-item-0'",HexFormat.of().parseHex(HASH));
        jdbc.update("UPDATE source_runs SET activation_stage='trial'");assertThrows(RuntimeException.class,this::selection);
    }
    @Test void actualSourceTimeUsesShanghaiStartInclusiveEndExclusiveAndNeverRetrievalTime() throws Exception {
        String p=payload(0,day.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());
        jdbc.update("UPDATE source_run_items SET evidence_json=?,evidence_hash=? WHERE id='fictional-item-0'",p,sha(p));
        assertEquals("ELIGIBLE",selection().entries().getFirst().reason().name());
        p=payload(0,day.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());
        jdbc.update("UPDATE source_run_items SET evidence_json=?,evidence_hash=? WHERE id='fictional-item-0'",p,sha(p));
        assertEquals("NOT_AI_ELIGIBLE",selection().entries().getFirst().reason().name());
        jdbc.update("UPDATE source_run_items SET target_business_date=? WHERE id='fictional-item-0'",java.sql.Date.valueOf(day.plusDays(1)));
        assertEquals("NOT_AI_ELIGIBLE",selection().entries().getFirst().reason().name());
    }
    @Test void damagedEvidenceOversizeAndWrongMetadataCannotBeSilentlyExcluded() {
        jdbc.update("UPDATE source_run_items SET evidence_json='{}' WHERE id='fictional-item-0'");assertThrows(RuntimeException.class,this::selection);
        jdbc.update("UPDATE source_run_items SET evidence_json=? WHERE id='fictional-item-0'","{\"fictional\":\""+"x".repeat(262145)+"\"}");assertThrows(RuntimeException.class,this::selection);
        jdbc.update("UPDATE source_runs SET business_date=?",java.sql.Date.valueOf(day.plusDays(1)));assertThrows(RuntimeException.class,this::selection);
    }
    @Test void emptyProofAndTransactionsHaveExplicitSemantics() {
        var empty=new MemberUniverse.Universe(universe.runId(),universe.sourceId(),HASH,List.of(),List.of(),0,0);
        assertTrue(tx.execute(a->service.read(empty,day,cutoff)).entries().isEmpty());
        assertThrows(RuntimeException.class,()->service.read(universe,day,cutoff));tx.setReadOnly(true);
        assertThrows(RuntimeException.class,this::selection);
    }
}
