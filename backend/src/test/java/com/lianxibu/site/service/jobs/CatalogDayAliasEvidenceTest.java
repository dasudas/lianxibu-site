package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.sources.RunManifestService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Two actual original collector members assigned by the native dedup writer to the same entity. */
class CatalogDayAliasEvidenceTest extends CatalogDayEntityEvidenceTest {
    class AliasFixture extends Fixture {
        @Override void recordCurrentCollectorRun(String manifest,String result){
            super.recordCurrentCollectorRun(manifest,result);var alias=current.deepCopy();((ObjectNode)alias.get("identity")).put("key","fictional-alias-object");String raw=JdbcFrozenInputs.canonical(alias),hash=CatalogPreviousAnalysisTest.Fixture.sha(CanonicalJson.bytes(alias));
            var first=new RunManifestService.Member("fictional-version-member","fictional-current-source-item","fictional-version-observation",CatalogPreviousAnalysisTest.Fixture.sha(CanonicalJson.bytes(current)),null);
            var second=new RunManifestService.Member("fictional-alias-member","fictional-alias-source-item","fictional-alias-observation",hash,null);var members=List.of(second,first);String partHash=RunManifestService.partHash(sourceRunId(),0,members);String complete=RunManifestService.manifestHash(sourceRunId(),2,List.of(new RunManifestService.Part(sourceRunId(),0,partHash,members)));
            var payload=JsonMapper.shared().createObjectNode().put("run_id",sourceRunId()).put("part_no",0).put("part_hash",partHash);var array=payload.putArray("members");for(var m:members)array.addObject().put("member_id",m.memberId()).put("source_item_id",m.sourceItemId()).put("observation_id",m.observationId()).put("payload_hash",m.payloadHash()).putNull("replaces_observation_id");var action=JsonMapper.shared().createObjectNode().put("operation","manifest");action.set("business",payload);
            assertEquals(1,f.jdbc.update("UPDATE request_receipts SET ack_json=?,business_hash=? WHERE receipt_kind='collector_control' AND control_operation='manifest'",JdbcFrozenInputs.canonical(payload),HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.CONTROL_BUSINESS,action))));
            var completedResult=JsonMapper.shared().createObjectNode().put("run_id",sourceRunId()).put("manifest_hash",complete).put("part_count",1).put("expected_count",2).put("fetch_outcome","succeeded");completedResult.putObject("coverage");
            f.jdbc.update("UPDATE source_runs SET manifest_hash=?,expected_count=2,accepted_count=2,result_json=? WHERE id=?",HexFormat.of().parseHex(complete),JdbcFrozenInputs.canonical(completedResult),sourceRunId());
            f.jdbc.update("INSERT INTO source_run_items(id,run_id,source_id,revision,category,created_at,terminal_state,state,projection_decision,effective_valid,evidence_json,evidence_hash,source_item_id,member_id,observation_id,payload_hash,part_no,target_business_date) VALUES(?,?,'fictional-source',3,?,?,'accepted','pending','initialized',1,?,?,?,?,?,?,0,'2026-01-01')","fictional-alias-item",sourceRunId(),f.category,java.sql.Timestamp.from(f.clock.instant().minusSeconds(1)),raw,HexFormat.of().parseHex(hash),second.sourceItemId(),second.memberId(),second.observationId(),HexFormat.of().parseHex(hash));
        }
        @Override void modelItem(ObjectNode item){if(item.get("item_id").asText().equals("fictional-alias-item"))item.put("reanalyze_recommended",false);}
    }
    @Test void FourCategoriesActualAliasAssignmentRetainsBothBodiesAndOneEntityWithoutIdentityRewriting(){
        for(String category:List.of("agent","skill","workflow","model")){
            var f=new AliasFixture();f.terminal(category);f.completeSource();assertEquals(2,f.f.jdbc.queryForObject("SELECT COUNT(*) FROM source_run_items WHERE run_id=? AND entity_id='fictional-entity' AND state='ready'",Integer.class,f.sourceRunId()));var provider=evidence(f,Clock.fixed(completed.plusSeconds(60),ZoneOffset.UTC));var captured=provider.capture(day);assertEquals(1,captured.entities().size());var group=captured.entities().getFirst();assertEquals(2,group.members().size());assertEquals(2,group.members().stream().map(CatalogDayEntityEvidenceService.Evidence::identityKey).distinct().count());assertEquals(Set.of("fictional-alias-item","fictional-current-item"),group.members().stream().map(e->e.member().itemId()).collect(java.util.stream.Collectors.toSet()));assertEquals(1,f.f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='catalog_version_judgment'",Integer.class));assertEquals(2,f.f.jdbc.queryForObject("SELECT analysis_generation FROM analyzed_entities",Integer.class));assertSame(captured,snapshot(f,()->provider.verifyObserved(captured)));var permits=new CatalogDayAnalysisCommitPermitService(provider,f.graph.permits(),new JdbcSourceDayDedupClosures(f.f.source),f.f.tx,category);var permission=permits.captureOriginal(day);assertEquals(captured.entities(),f.write(()->permits.verify(permission)).entities());assertEquals(2,f.f.sends);
        }
    }
    @Test void ActualOriginalAliasBodiesReplayAfterCurrentDeletionAndNativePlanCorruptionStillRejects(){
        var f=new AliasFixture();f.terminal("agent");f.completeSource();var provider=evidence(f,Clock.fixed(completed.plusSeconds(60),ZoneOffset.UTC));var original=provider.capture(day);int jobs=f.f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class);f.f.jdbc.update("DELETE FROM source_run_items WHERE run_id=?",f.sourceRunId());f.f.jdbc.execute("DROP TABLE sources");assertEquals(original.entities(),evidence(f,mock(Clock.class)).captureOriginal(day).entities());assertTrue(f.f.jdbc.update("UPDATE jobs SET payload_hash=? WHERE record_kind='snapshot_part' AND owner_job_id=? AND stamp_id=?",new byte[32],f.reference.ownerId(),JdbcFrozenSourcePlans.stamp(f.reference.ownerId()))>0);assertThrows(RuntimeException.class,()->provider.captureOriginal(day));assertEquals(jobs,f.f.jdbc.queryForObject("SELECT COUNT(*) FROM jobs",Integer.class));assertEquals(2,f.f.sends);
    }
}
