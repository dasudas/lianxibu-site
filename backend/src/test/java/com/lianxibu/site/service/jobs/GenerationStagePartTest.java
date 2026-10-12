package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.GenerationFlowService.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

class GenerationStagePartTest {
    GenerationJdbcStoreTest fixture;
    GenerationJdbcStoreTest fixture(){return new GenerationJdbcStoreTest();}
    @BeforeEach void setup(){fixture=fixture();fixture.fixture();}
    Prepare command(long part) {
        var original=fixture.command(false);
        return new Prepare("fictional-part-key",1,Stage.NEWS_VALUE,original.businessDate(),"fictional-source",
                List.of(),original.evidence(),List.of(),true,original.settingsHash(),null,List.of(),part);
    }
    Frozen prepare(Prepare command,String owner,String action) {
        var store=new GenerationJdbcStore(fixture.source,fixture.clock,"fictional-owner",owner,command);
        return fixture.flow(store).prepare(command,new SharedWriteExecutor.ActionContext(action,owner,fixture.HASH));
    }
    @Test void sameGenerationPartsOwnIndependentPermanentRootsInputsAndAttemptBudgets() {
        var first=prepare(command(0),"fictional-part-root-0","fictional-part-0");
        var second=prepare(command(1),"fictional-part-root-1","fictional-part-1");
        assertNotEquals(first.ownerId(),second.ownerId());assertNotEquals(first.inputHash(),second.inputHash());
        assertEquals(1,CanonicalJson.parse(second.snapshotJson()).path("stage_part").asLong());
        assertEquals(List.of(0L,1L),fixture.jdbc.queryForList("SELECT stage_part FROM jobs WHERE record_kind='logical_task' ORDER BY stage_part",Long.class));
        assertEquals(1,fixture.count("record_kind='generation_intent'"));assertEquals(2,fixture.count("record_kind='ai_attempt'"));
        assertEquals(0,fixture.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
        assertEquals(first,prepare(command(0),first.ownerId(),"fictional-part-0"));
        assertEquals(second,prepare(command(1),second.ownerId(),"fictional-part-1"));
        assertThrows(RuntimeException.class,()->prepare(command(2),second.ownerId(),"fictional-part-1"));
    }
    @Test void removingOrChangingPartCannotSaveEvenWithRehashedSnapshotAndRequest() {
        for(boolean remove:List.of(false,true)) {
            var command=command(1);String owner="fictional-tamper-"+remove;
            var store=new GenerationJdbcStore(fixture.source,fixture.clock,"fictional-owner",owner,command);
            FrozenStore intercept=new FrozenStore(){public Frozen committed(String key){return null;}public void save(Frozen value,String key){
                var input=(tools.jackson.databind.node.ObjectNode)CanonicalJson.parse(value.snapshotJson());if(remove)input.remove("stage_part");else input.put("stage_part",2);
                String hash=CanonicalJson.hash(CanonicalJson.Domain.INPUT,input);var request=(tools.jackson.databind.node.ObjectNode)CanonicalJson.parse(value.requestJson());request.put("input_hash",hash);
                store.save(new Frozen(value.ownerId(),value.logicalKey(),value.generation(),value.stage(),value.cutoff(),value.coverageState(),value.missing(),JdbcFrozenInputs.canonical(input),hash,JdbcFrozenInputs.canonical(request),value.priority(),value.eligibleAt(),value.state()),key);
            }};
            assertThrows(RuntimeException.class,()->fixture.flow(intercept).prepare(command,new SharedWriteExecutor.ActionContext("fictional-tamper-part-"+remove,owner,fixture.HASH)));
            assertEquals(0,fixture.count("1=1"));
        }
    }
    @Test void invalidOrUnsupportedPartsAndNewGenerationWithoutOriginalPredecessorRefusePreparation() {
        for(long part:List.of(-1L,10000L))assertThrows(RuntimeException.class,()->prepare(command(part),"fictional-invalid-part","fictional-invalid-part"));
        var original=fixture.command(false);var report=new Prepare(original.logicalKey(),1,Stage.DAILY_REPORT,original.businessDate(),null,List.of(),original.evidence(),List.of(),true,original.settingsHash(),null,List.of(),1);
        assertThrows(RuntimeException.class,()->prepare(report,"fictional-report-part","fictional-report-part"));
        var c=command(1);var next=new Prepare(c.logicalKey(),2,c.stage(),c.businessDate(),c.sourceId(),c.dependencies(),c.evidence(),c.expectedDependencies(),c.explicitSupplement(),c.settingsHash(),null,List.of(),c.stagePart());
        assertThrows(RuntimeException.class,()->prepare(next,"fictional-new-part-generation","fictional-new-part-generation"));assertEquals(0,fixture.count("1=1"));
    }
}
