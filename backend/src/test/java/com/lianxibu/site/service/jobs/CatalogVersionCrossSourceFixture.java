package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.config.ResultApplicationFactory;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.repository.sources.*;
import com.lianxibu.site.repository.articles.JdbcSourceMemberEvidence;
import com.lianxibu.site.repository.content.JdbcContentReads;
import com.lianxibu.site.service.articles.SourceMemberEvidenceService;
import com.lianxibu.site.service.content.ContentReadService;
import com.lianxibu.site.service.sources.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Two actual original fictional source handoffs; transport remains fictional. */
class CatalogVersionCrossSourceFixture extends CatalogVersionSourceApplicationTest {
    static final String SECOND_RUN="fictional-second-source-run",SECOND_ITEM="fictional-second-item";
    CatalogSourcePlanPreparationCoordinator.Prepared second;
    void cloneRow(String table,String oldId,Map<String,Object> changes){
        var row=new LinkedHashMap<String,Object>();f.jdbc.queryForMap("SELECT * FROM "+table+" WHERE id=?",oldId).forEach((k,v)->row.put(k.toLowerCase(Locale.ROOT),v));row.putAll(changes);
        f.jdbc.update("INSERT INTO "+table+" ("+String.join(",",row.keySet())+") VALUES ("+String.join(",",Collections.nCopies(row.size(),"?"))+")",row.values().toArray());
    }
    void prepareSecondOriginalSource(){
        var member=new RunManifestService.Member("fictional-second-member","fictional-second-source-item","fictional-second-observation",CatalogPreviousAnalysisTest.Fixture.sha(CanonicalJson.bytes(current)),null);
        String partHash=RunManifestService.partHash(SECOND_RUN,0,List.of(member));String manifest=RunManifestService.manifestHash(SECOND_RUN,1,List.of(new RunManifestService.Part(SECOND_RUN,0,partHash,List.of(member))));
        var payload=JsonMapper.shared().createObjectNode().put("run_id",SECOND_RUN).put("part_no",0).put("part_hash",partHash);payload.putArray("members").addObject().put("member_id",member.memberId()).put("source_item_id",member.sourceItemId()).put("observation_id",member.observationId()).put("payload_hash",member.payloadHash()).putNull("replaces_observation_id");
        var control=JsonMapper.shared().createObjectNode().put("operation","manifest");control.set("business",payload);
        f.jdbc.update("INSERT INTO sources VALUES('fictional-second-source',?)",f.category);
        var result=JsonMapper.shared().createObjectNode().put("run_id",SECOND_RUN).put("manifest_hash",manifest).put("part_count",1).put("expected_count",1).put("fetch_outcome","succeeded");result.putObject("coverage");
        cloneRow("source_runs",RUN,Map.of("id",SECOND_RUN,"source_id","fictional-second-source","manifest_hash",HexFormat.of().parseHex(manifest),"result_json",JdbcFrozenInputs.canonical(result),"job_refs_json","[]"));
        cloneRow("source_run_items","fictional-current-item",Map.of("id",SECOND_ITEM,"run_id",SECOND_RUN,"source_id","fictional-second-source","source_item_id",member.sourceItemId(),"member_id",member.memberId(),"observation_id",member.observationId()));
        f.jdbc.update("INSERT INTO request_receipts VALUES('fictional-second-part',? ,?,'collector_control','manifest',?,?)",SECOND_RUN,"manifest:"+SECOND_RUN+":0",JdbcFrozenInputs.canonical(payload),HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.CONTROL_BUSINESS,control)));
        var handoff=new CollectionHandoffService(new JdbcCollectionHandoff(f.source),new CollectionIntentService(f.source,f.clock)::ensure);
        write(()->{handoff.terminal(SECOND_RUN);return true;});var proof=write(()->graph.collections().read(SECOND_RUN));second=graph.coordinator().prepareDedup(proof.intentId(),SECOND_RUN,0);
    }
    void sendSecond(){
        var root=second.root();var inputs=new JdbcFrozenInputs(f.source);
        var dispatch=new DispatchReadinessService(new JdbcDispatchInputs(f.source),inputs,new FrozenDependencyIndexService(inputs,new JdbcFrozenDependencies(f.source)),new GenerationLineageService(new JdbcGenerationLineage(f.source),f.clock,"analysis-worker"),new ContentReadService(new JdbcContentReads(f.source),f.tx,ContentReadService.denyUnverifiedReferences()),new SourceMemberEvidenceService(new JdbcSourceMemberEvidence(f.source)),target->{throw new AssertionError("unexpected original reference");},collections::read,new SourcePartitionProofService(plans),true);
        assertTrue(write(()->dispatch.ready(root.ownerId())));
        var worker=new WorkerAuthorizationService(f.writes,new WorkerJdbcStore(new JdbcWorkerState(f.source),new JdbcJobRecords(f.source),"fictional-second-epoch",()->false,dispatch::ready,f.clock),f.clock,()->true);
        var claim=worker.claim(new SharedWriteExecutor.ActionContext("fictional-second-claim",root.ownerId(),root.inputHash()));assertEquals(WorkerAuthorizationService.State.CLAIMED,claim.state());f.permit=claim.permit();f.identity=f.permit.identity();
        assertEquals(WorkerAuthorizationService.State.SENT,worker.dispatch(f.permit,request->{assertEquals(CanonicalJson.parse(root.requestJson()),CanonicalJson.parse(request));f.sends++;return "fictional second model response";}).state());
        application=new ResultApplicationFactory(f.source,f.tx,f.clock).forAttempt(f.identity);
        var payload=JsonMapper.shared().createObjectNode().put("kind","dedup").put("dedup_result_version","2").put("summary","Fictional second source result");var item=payload.putArray("items").addObject().put("item_id",SECOND_ITEM).put("category",f.category).put("decision","existing").put("candidate_entity_id","fictional-entity").put("reason","Fictional verified second input").put("confidence","0.9").put("suggested_title","Fictional current catalog").putNull("suggested_summary").put("reanalyze_recommended",true);
        var q=application.input().catalogVersionQualifications().get(SECOND_ITEM);item.put("version_changed",q.versionChanged());item.putObject("previous_evidence_ref").put("kind","catalog_version_evidence").put("proof_hash",q.previousProofHash());item.putObject("new_evidence_ref").put("kind","catalog_version_evidence").put("proof_hash",q.newProofHash());
        raw=ResultApplicationService.providerEnvelope(application.input(),JdbcFrozenInputs.canonical(payload));action="fictional-second-source-apply";
    }
}
