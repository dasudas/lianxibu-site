package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.GenerationFlowService.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.node.ObjectNode;

/** Only original context persistence is qualified; all version proof references and collection inputs are fictional. */
class FrozenCatalogVersionContextStoreTest {
    GenerationJdbcStoreTest f;Prepare command;GenerationJdbcStore store;Frozen frozen;boolean mysql(){return false;}
    @BeforeEach void setup(){
        f=mysql()?new GenerationJdbcStoreMySqlTest():new GenerationJdbcStoreTest();f.fixture();
        f.writes.execute(new SharedWriteExecutor.ActionContext("fictional-collection-seed","fictional-collection","c".repeat(64)),k->null,a->{
            var records=new JdbcJobRecords(f.source);records.ensureIntent(new JdbcJobRecords.Intent("fictional-collection-intent","fictional-collection-key",1,"agent_dedup",LocalDate.of(2026,1,1)),f.clock.instant());
            records.createRoot(new JdbcJobRecords.Root("fictional-collection","fictional-collection-intent","fictional-collection-key",1,0,"agent_dedup",3),f.clock.instant());
            f.jdbc.update("UPDATE jobs SET state='succeeded' WHERE id=?","fictional-collection");return true;
        });
        var context=FrozenApplicationContext.fromJson(new FrozenCatalogVersionContextTest().context(Stage.AGENT_DEDUP));
        var evidence=new Evidence("fictional-item","catalog",LocalDate.of(2026,1,1),Instant.parse("2026-01-01T20:00:00Z"),"c".repeat(64),"{\"fictional\":true}",true,false,"accepted",List.of());
        var dependency=new Dependency("fictional-collection",Stage.AGENT_DEDUP,"succeeded",Instant.parse("2026-01-01T20:00:00Z"));
        command=new Prepare("fictional-version-context-key",1,Stage.AGENT_DEDUP,LocalDate.of(2026,1,1),null,List.of(dependency),List.of(evidence),List.of("fictional-collection"),true,"c".repeat(64),context);
        store=new GenerationJdbcStore(f.source,f.clock,"fictional-owner","fictional-root",command);frozen=f.flow(store).prepare(command,f.action());
    }
    @Test void NativeOriginalInputPersistsFullVersionContextAndSameReplayNeverAuthorizesAWorker(){
        assertEquals("queued",frozen.state());assertNotNull(frozen.inputHash());assertNotNull(frozen.requestJson());
        var snapshot=CanonicalJson.parse(new JdbcFrozenInputs(f.source).read("fictional-root",frozen.inputHash()));
        var context=FrozenApplicationContext.fromJson(snapshot.get(FrozenApplicationContext.FIELD));var input=context.input("fictional-root","fictional-attempt",frozen.inputHash(),0);
        assertEquals("2",input.dedupResultVersion());assertEquals("a".repeat(64),input.catalogVersionQualifications().get("fictional-item").previousProofHash());assertEquals("b".repeat(64),input.catalogVersionQualifications().get("fictional-item").newProofHash());
        assertEquals(frozen,store.committed(GenerationJdbcStoreTest.ACTION));assertEquals(0,new JdbcJobRecords(f.source).byId("fictional-root").callCount());assertEquals(1,f.count("record_kind='ai_attempt'"));
    }
    @Test void NativeReplayCannotReplaceOriginalSideReferencesWithRehashedCallerContext(){
        var json=(ObjectNode)command.applicationContext().toJson();((ObjectNode)json.get("catalog_version_qualifications").get(0).get("new_evidence_ref")).put("proof_hash","d".repeat(64));
        var changed=new Prepare(command.logicalKey(),command.generation(),command.stage(),command.businessDate(),command.sourceId(),command.dependencies(),command.evidence(),command.expectedDependencies(),command.explicitSupplement(),command.settingsHash(),FrozenApplicationContext.fromJson(json));
        assertThrows(RuntimeException.class,()->new GenerationJdbcStore(f.source,f.clock,"fictional-owner","fictional-root",changed).committed(GenerationJdbcStoreTest.ACTION));
        assertEquals(frozen,store.committed(GenerationJdbcStoreTest.ACTION));assertEquals(0,new JdbcJobRecords(f.source).byId("fictional-root").callCount());
    }
}
