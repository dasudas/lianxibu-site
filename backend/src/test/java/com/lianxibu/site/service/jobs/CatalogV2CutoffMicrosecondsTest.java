package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.ResultApplicationService.Status;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Actual original native INPUT with whole seconds and shorter fractions; no permissive parser or fixture time workaround. */
class CatalogV2CutoffMicrosecondsTest extends CatalogDayFullNativeIntegrationTest {
    @Test void WholeSecondAndShortFractionOriginalV2InputsKeepStrictSixMicrosecondsAndApply(){
        for(String category:List.of("agent","skill","workflow","model"))qualify(category,0);
        for(int nanos:List.of(1000,123000000,123456000))qualify("agent",nanos);
    }
    void qualify(String category,int nanos){
        var fixture=new Fixture(){@Override CatalogPreviousVersionProfileTest.Fixture fixture(){var value=super.fixture();value.clock=Clock.fixed(completed.plusNanos(nanos),ZoneOffset.UTC);return value;}};
        fixture.terminal(category);var original=fixture.graph.coordinator().prepareDedup(fixture.collection.intentId(),fixture.sourceRunId(),0).root();var input=CanonicalJson.parse(original.snapshotJson());assertTrue(input.get("cutoff").asText().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}Z"));assertEquals(completed.plusNanos(nanos),WireValues.utcMicroseconds(input.get("cutoff")));assertEquals(original.cutoff(),WireValues.utcMicroseconds(input.get("cutoff")));assertEquals(Status.APPLIED,fixture.apply().status());assertEquals(2,fixture.f.jdbc.queryForObject("SELECT analysis_generation FROM analyzed_entities",Integer.class));assertEquals(1,new JdbcJobRecords(fixture.f.source).byId(original.ownerId()).callCount());assertEquals(2,fixture.f.sends);
    }
    @Test void RebuiltGraphKeepsTheSameOriginalInputAndRequestBeforeAnyLaterCurrentFacts(){
        var fixture=new Fixture();fixture.terminal("skill");var original=fixture.graph.coordinator().prepareDedup(fixture.collection.intentId(),fixture.sourceRunId(),0).root();int rows=fixture.f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class);fixture.f.jdbc.update("DELETE FROM source_run_items WHERE id='fictional-current-item'");fixture.graph();var replay=fixture.graph.coordinator().prepareDedup(fixture.collection.intentId(),fixture.sourceRunId(),0).root();assertEquals(original.snapshotJson(),replay.snapshotJson());assertEquals(original.requestJson(),replay.requestJson());assertEquals(original.inputHash(),replay.inputHash());assertEquals(rows,fixture.f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class));assertEquals(2,fixture.f.sends);
    }
}
