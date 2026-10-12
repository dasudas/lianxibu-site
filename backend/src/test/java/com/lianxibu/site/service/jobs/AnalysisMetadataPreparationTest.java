package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.articles.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.articles.*;
import com.lianxibu.site.service.content.FrozenPublicMetadata;
import com.lianxibu.site.service.jobs.GenerationFlowService.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.node.ObjectNode;

class AnalysisMetadataPreparationTest {
    @Test void realEvidenceIsFrozenBeforeRequestAndPermanentReplaySurvivesCurrentChanges() throws Exception {
        var f=new SourceMemberEvidenceServiceTest();f.setup();
        String payload="""
            {"schema_version":"1","parser_version":"fictional-parser-v1","config_version":1,"category":"agent",
             "identity":{"namespace":"github_repository","key":"fictional/agent"},"title":"Fictional frozen agent","source_url":"https://example.invalid/agent",
             "version":null,"published_at":null,"updated_at":null,"summary":null,"document_text":null,"truncated":false,
             "evidence":[{"url":"https://example.invalid/agent","revision":null,"path":null,"file_hash":null,"evidence_type":"metadata"}]}
            """;
        f.jdbc.execute("ALTER TABLE source_runs ADD business_date DATE DEFAULT DATE '2026-01-01'");f.jdbc.execute("ALTER TABLE source_run_items ADD entity_id VARCHAR(64)");f.jdbc.execute("ALTER TABLE source_run_items ADD source_item_id VARCHAR(256) DEFAULT 'fictional-source-item'");
        var body=CanonicalJson.parse(payload);String identity=new String(CanonicalJson.bytes(body.get("identity")),java.nio.charset.StandardCharsets.UTF_8);
        f.jdbc.update("UPDATE source_run_items SET entity_id='fictional-entity',state='ready',category='agent',evidence_json=?,evidence_hash=?",payload,java.security.MessageDigest.getInstance("SHA-256").digest(CanonicalJson.bytes(body)));
        f.jdbc.execute("CREATE TABLE analyzed_entities(id VARCHAR(64),category VARCHAR(16),identity_key VARCHAR(4096),identity_hash BINARY(32),first_seen_business_date DATE,occurrence_count BIGINT,legacy_flag INT,revision BIGINT)");
        f.jdbc.update("INSERT INTO analyzed_entities VALUES('fictional-entity','agent',?,?,DATE '2026-01-01',1,0,3)",identity,JdbcEntityDedup.identityHash("agent",identity));
        var tables=new GenerationJdbcStoreTest();tables.source=f.source;tables.jdbc=f.jdbc;tables.schema();
        f.jdbc.execute("CREATE ALIAS HEX FOR 'com.lianxibu.site.service.jobs.ResultJdbcStoreTest.hex'");f.jdbc.execute("CREATE ALIAS UNHEX FOR 'com.lianxibu.site.service.jobs.ResultJdbcStoreTest.unhex'");
        var target=CanonicalJson.parse("{\"kind\":\"catalog_evidence\",\"item_id\":\"fictional-item\"}");var contextNode=(ObjectNode)CanonicalJson.parse(FrozenApplicationContextTest.context(Stage.AGENT_ANALYSIS));contextNode.putArray("reference_targets").add(target);contextNode.putArray("allowed_citation_ids").add(AnalysisPublicMetadataEnricher.citationId(target));var context=FrozenApplicationContext.fromJson(contextNode);
        var command=new Prepare("fictional-analysis-key",1,Stage.AGENT_ANALYSIS,LocalDate.of(2026,1,1),null,List.of(),List.of(new Evidence("fictional-item","catalog_evidence",LocalDate.of(2026,1,1),f.cutoff.minusSeconds(1),CanonicalJson.hash(CanonicalJson.Domain.CONTENT,body),payload,true,false,"accepted",List.of())),List.of(),true,"a".repeat(64),context);
        var clock=Clock.fixed(f.cutoff,ZoneOffset.UTC);var writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(f.source),new DataSourceTransactionManager(f.source));var store=new GenerationJdbcStore(f.source,clock,"fictional-owner","fictional-root",command);
        var enricher=new AnalysisPublicMetadataEnricher(new EntityInputEvidenceService(new JdbcEntityDedup(f.source),f.service),new ArticlePublicEvidenceService(new JdbcArticlePublicEvidence(f.source),f.service));
        var action=new SharedWriteExecutor.ActionContext("fictional-prepare","fictional-root","a".repeat(64));
        assertThrows(IllegalStateException.class,()->new GenerationFlowService(writes,store,(stage,snapshot,hash)->{throw new IllegalStateException("Fictional request failed");},clock,()->true,null,enricher).prepare(command,action));
        assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class));
        var flow=new GenerationFlowService(writes,store,(stage,snapshot,hash)->{var root=CanonicalJson.parse(snapshot);assertEquals(hash,CanonicalJson.hash(CanonicalJson.Domain.INPUT,root));assertEquals("Fictional frozen agent",FrozenPublicMetadata.read(root.get(FrozenPublicMetadata.FIELD),context).interpretation().name());return "{\"provider\":\"fictional\",\"model\":\"fictional\",\"input_hash\":\""+hash+"\",\"messages\":[{\"role\":\"user\",\"content\":\"Fictional input\"}],\"response_format\":{},\"parameters\":{},\"schema_version\":\"1\",\"prompt_version\":\"1\"}";},clock,()->true,null,enricher);
        var frozen=flow.prepare(command,action);assertEquals("queued",frozen.state());assertEquals(CanonicalJson.parse(frozen.snapshotJson()),CanonicalJson.parse(new JdbcFrozenInputs(f.source).read("fictional-root",frozen.inputHash())));
        f.jdbc.update("UPDATE source_run_items SET revision=90,evidence_json='{}'");
        var replay=new GenerationJdbcStore(f.source,clock,"fictional-owner","fictional-root",command).committed("fictional-prepare");assertEquals(frozen,replay);assertTrue(replay.snapshotJson().contains("Fictional frozen agent"));
    }
}
