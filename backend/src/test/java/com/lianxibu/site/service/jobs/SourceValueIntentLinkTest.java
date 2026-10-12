package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.sources.JdbcSourceValueIntentLinks;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Storage port only; the next consumer supplies a validated native child intent. */
class SourceValueIntentLinkTest {
    NewsSourcePlanPreparationTest f;JdbcSourceValueIntentLinks service;
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    @BeforeEach void setup() throws Exception {f=new NewsSourcePlanPreparationTest(){@Override GenerationJdbcStoreTest fixture(){return SourceValueIntentLinkTest.this.database();}};f.setup();service=new JdbcSourceValueIntentLinks(f.f.source);}
    <T>T readonly(java.util.function.Supplier<T> work){var tx=new TransactionTemplate(new DataSourceTransactionManager(f.f.source));tx.setReadOnly(true);return tx.execute(s->work.get());}
    JdbcSourceValueIntentLinks.Header read(){return readonly(()->service.read("fictional-run"));}
    String refs(){return f.f.jdbc.queryForObject("SELECT job_refs_json FROM source_runs WHERE id='fictional-run'",String.class);}
    @Test void appendAndReplayPreserveOriginalCollectorFactsAndOnlyAdvanceRevisionOnce(){
        var before=read();String config=f.f.jdbc.queryForObject("SELECT config_snapshot_json FROM source_runs WHERE id='fictional-run'",String.class);assertTrue(before.refs().contains(f.collection.intentId()));
        assertEquals(before.revision()+1,f.tx(()->service.link("fictional-run",f.collection.intentId(),"fictional-next-intent")));var after=read();assertEquals(before.revision()+1,after.revision());assertEquals(before.manifestHash(),after.manifestHash());assertEquals(before.accepted(),after.accepted());assertEquals(before.excluded(),after.excluded());assertEquals(config,f.f.jdbc.queryForObject("SELECT config_snapshot_json FROM source_runs WHERE id='fictional-run'",String.class));
        assertEquals(List.of(f.collection.intentId(),"fictional-next-intent"),after.refs());assertEquals(after.revision(),f.tx(()->new JdbcSourceValueIntentLinks(f.f.source).link("fictional-run",f.collection.intentId(),"fictional-next-intent")));assertEquals(after.revision(),read().revision());
    }
    @Test void outerFailureRollsBackTheNewReferenceAndRevision(){
        var before=read();String refs=refs();assertThrows(RuntimeException.class,()->f.tx(()->{service.link("fictional-run",f.collection.intentId(),"fictional-next-intent");throw new IllegalStateException("fictional downstream receipt rejected");}));assertEquals(refs,refs());assertEquals(before.revision(),read().revision());
    }
    @Test void missingOriginalReferenceBadAliasAndReadonlyWritesAreRejected(){
        assertThrows(RuntimeException.class,()->service.read("fictional-run"));assertThrows(RuntimeException.class,()->readonly(()->service.link("fictional-run",f.collection.intentId(),"fictional-next-intent")));assertThrows(RuntimeException.class,()->readonly(()->service.read("FICTIONAL-RUN")));
        assertThrows(RuntimeException.class,()->f.tx(()->service.link("fictional-run","fictional-missing-original","fictional-next-intent")));assertThrows(RuntimeException.class,()->f.tx(()->service.link("fictional-run",f.collection.intentId(),f.collection.intentId())));assertNotNull(read());
    }
    @Test void malformedDuplicateHugeReferencesAndInconsistentCountsAreRejectedBeforeLinking(){
        String saved=refs();for(String bad:List.of("{}","[\"fictional-duplicate\",\"fictional-duplicate\"]","[\"fictional bad space\"]","[\""+"F".repeat(65536)+"\"]")){f.f.jdbc.update("UPDATE source_runs SET job_refs_json=? WHERE id='fictional-run'",bad);assertThrows(RuntimeException.class,this::read);assertThrows(RuntimeException.class,()->f.tx(()->service.link("fictional-run",f.collection.intentId(),"fictional-next-intent")));}
        f.f.jdbc.update("UPDATE source_runs SET job_refs_json=? WHERE id='fictional-run'",saved);f.f.jdbc.update("UPDATE source_runs SET expected_count=99 WHERE id='fictional-run'");assertThrows(RuntimeException.class,this::read);
    }
}
