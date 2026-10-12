package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.node.ObjectNode;

class SourceDedupDependencyServiceTest {
    SourceDedupStageAvailabilityServiceTest f;SourceDedupDependencyService service;
    SourceDedupStageAvailabilityServiceTest fixture(){return new SourceDedupStageAvailabilityServiceTest();}
    @BeforeEach void setup(){f=fixture();f.setup();service=new SourceDedupDependencyService(f.store);}
    FrozenSourceDedupDependency complete(){f.f.complete(0);f.f.complete(1);Instant observed=f.service.capture(f.f.ref);return FrozenSourceDedupDependency.create(f.f.read(),observed,observed);}
    SourceDedupDependencyService.Proven read(FrozenSourceDedupDependency original,Instant cutoff){return f.f.f.readonly(()->service.read(original.toJson(),cutoff));}
    @Test void theNativeCompleteObservationProvesOriginalDependencyInEitherTransactionWithoutWrites() {
        var original=complete();int count=f.f.f.f.count("1=1");var result=read(original,original.observedAt());assertEquals(original.json(),result.dependency().json());assertEquals(1,result.generation());assertEquals("fictional-source",result.sourceId());
        assertEquals(original.json(),f.f.f.tx(()->service.read(original.toJson(),original.observedAt())).dependency().json());assertEquals(count,f.f.f.f.count("1=1"));assertEquals(2,f.f.f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
        assertThrows(RuntimeException.class,()->read(original,original.observedAt().minusNanos(1000)));assertThrows(RuntimeException.class,()->read(original,original.observedAt().plusNanos(1)));
        assertThrows(RuntimeException.class,()->service.read(original.toJson(),original.observedAt()));assertFalse(result.toString().contains("Fictional fragment summary"));
    }
    @Test void structurallyValidPureDependencyCannotPretendThereWasANativeObservation() {
        f.f.complete(0);f.f.complete(1);var original=FrozenSourceDedupDependency.create(f.f.read(),f.cutoff().plusNanos(1000),f.cutoff().plusNanos(1000));assertThrows(RuntimeException.class,()->read(original,original.observedAt()));assertEquals(0,f.observations());
    }
    @Test void completeZeroPlanRequiresItsOwnExactOriginalObservation() {
        f.f.install(0);f.installObserver();service=new SourceDedupDependencyService(f.store);var original=f.f.read();var fake=FrozenSourceDedupDependency.create(original,f.cutoff().plusNanos(1000),f.cutoff().plusNanos(1000));assertThrows(RuntimeException.class,()->read(fake,fake.observedAt()));
        Instant observed=f.service.capture(f.f.ref);var dependency=FrozenSourceDedupDependency.create(original,observed,observed);assertTrue(read(dependency,observed).dependency().dependencies().isEmpty());assertEquals(1,f.observations());assertEquals(0,f.f.f.f.count("record_kind='logical_task'"));
    }
    @Test void changedOriginalContentAndRehashedIdentityCannotBorrowTheExistingObservation() {
        var original=complete();var changed=(ObjectNode)original.toJson();var full=(ObjectNode)changed.get("stage_result");((ObjectNode)full.get("fragments").get(0).get("payload")).put("summary","Fictional changed summary");
        assertThrows(RuntimeException.class,()->f.f.f.readonly(()->service.read(changed,original.observedAt())));
        ((ObjectNode)changed.get("stage_key")).put("result_hash",CanonicalJson.hash(CanonicalJson.Domain.CONTENT,full));((ObjectNode)changed.get("reports").get(0)).put("summary","Fictional changed summary");
        assertDoesNotThrow(()->FrozenSourceDedupDependency.read(changed,original.observedAt()));assertThrows(RuntimeException.class,()->f.f.f.readonly(()->service.read(changed,original.observedAt())));assertEquals(1,f.observations());
        var later=(ObjectNode)original.toJson();later.put("observed_at",original.observedAt().plusSeconds(1).toString());assertThrows(RuntimeException.class,()->f.f.f.readonly(()->service.read(later,original.observedAt().plusSeconds(2))));
    }
    @Test void rehashedBadNativeLedgerAndWrongRolesNeverGrantOriginalDependency() {
        var original=complete();var jdbc=f.f.f.f.jdbc;String id=jdbc.queryForObject("SELECT id FROM jobs WHERE target_type='source_stage_availability'",String.class);String saved=jdbc.queryForObject("SELECT action_payload FROM jobs WHERE id=?",String.class,id);
        for(String mode:List.of("schema_version","proof_hash","observed_at","extra")) {
            var node=(ObjectNode)CanonicalJson.parse(saved);node.put(mode,mode.equals("observed_at")?original.observedAt().plusNanos(1).toString():"fictional-wrong");String raw=JdbcFrozenInputs.canonical(node);jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=? WHERE id=?",raw,JdbcGenerationLineage.hash(raw),id);assertThrows(RuntimeException.class,()->read(original,original.observedAt()));
        }
        jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=?,operator_id='fictional-other' WHERE id=?",saved,JdbcGenerationLineage.hash(JdbcFrozenInputs.canonical(CanonicalJson.parse(saved))),id);assertThrows(RuntimeException.class,()->read(original,original.observedAt()));
    }
    @Test void frozenOriginalProofDoesNotQueryTheCurrentBusinessTables() {
        var original=complete();var jdbc=f.f.f.f.jdbc;jdbc.execute("DROP TABLE IF EXISTS source_run_items");jdbc.execute("DROP TABLE IF EXISTS source_runs");assertEquals(original.json(),read(original,original.observedAt().plusSeconds(1)).dependency().json());assertEquals(1,f.observations());
    }
}
