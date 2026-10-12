package com.lianxibu.site.service.articles;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.articles.JdbcEntityDedup;
import java.util.*;
import org.junit.jupiter.api.*;

class EntityInputEvidenceServiceTest {
    SourceMemberEvidenceServiceTest f;EntityInputEvidenceService service;
    @BeforeEach void setup() throws Exception {
        f=new SourceMemberEvidenceServiceTest();f.setup();
        f.jdbc.execute("ALTER TABLE source_runs ADD business_date DATE DEFAULT DATE '2026-01-01'");
        f.jdbc.execute("ALTER TABLE source_run_items ADD entity_id VARCHAR(64)");f.jdbc.execute("ALTER TABLE source_run_items ADD source_item_id VARCHAR(256) DEFAULT 'fictional-source-item'");
        f.jdbc.update("UPDATE source_run_items SET entity_id='fictional-entity',state='ready'");
        f.jdbc.execute("CREATE TABLE analyzed_entities(id VARCHAR(64),category VARCHAR(16),identity_key VARCHAR(4096),identity_hash BINARY(32),first_seen_business_date DATE,occurrence_count BIGINT,legacy_flag INT,revision BIGINT)");
        f.jdbc.update("INSERT INTO analyzed_entities VALUES('fictional-entity','news_tech','fictional-canonical',?,DATE '2026-01-01',1,0,3)",JdbcEntityDedup.identityHash("news_tech","fictional-canonical"));
        service=new EntityInputEvidenceService(new JdbcEntityDedup(f.source),f.service);
    }
    EntityInputEvidenceService.Selection read(){return f.tx.execute(s->service.read("fictional-entity","news_tech",Set.of("fictional-item"),f.cutoff));}
    @Test void provenSourceAliasKeepsExplicitCurrentAssignmentAndOriginalVersion(){var value=read();assertEquals(3,value.entityRevision());assertEquals(2,value.members().getFirst().revision());assertEquals("Fictional source title",value.members().getFirst().title());assertThrows(UnsupportedOperationException.class,()->value.members().clear());}
    @Test void changedAssignmentStateAndEntityIdentityReject(){
        f.jdbc.update("UPDATE source_run_items SET entity_id='fictional-other'");assertThrows(RuntimeException.class,this::read);
        f.jdbc.update("UPDATE source_run_items SET entity_id='fictional-entity',state='pending'");assertThrows(RuntimeException.class,this::read);
        f.jdbc.update("UPDATE source_run_items SET state='ready'");f.jdbc.update("UPDATE analyzed_entities SET identity_hash=?",new byte[32]);assertThrows(RuntimeException.class,this::read);
    }
    @Test void categoryLegacyInvalidAndMissingTransactionReject(){
        assertThrows(RuntimeException.class,()->service.read("fictional-entity","news_tech",Set.of("fictional-item"),f.cutoff));
        assertThrows(RuntimeException.class,()->f.tx.execute(s->service.read("fictional-entity","model",Set.of("fictional-item"),f.cutoff)));
        f.jdbc.update("UPDATE analyzed_entities SET legacy_flag=1");assertThrows(RuntimeException.class,this::read);
        f.jdbc.update("UPDATE analyzed_entities SET legacy_flag=0");f.jdbc.update("UPDATE source_run_items SET effective_valid=0");assertThrows(RuntimeException.class,this::read);
    }
}
