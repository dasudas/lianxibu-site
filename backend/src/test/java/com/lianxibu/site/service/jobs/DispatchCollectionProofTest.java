package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.articles.SourceMemberEvidenceService;
import com.lianxibu.site.service.content.ContentReadService;
import com.lianxibu.site.service.jobs.GenerationFlowService.*;
import java.util.*;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.*;
import tools.jackson.databind.node.ObjectNode;

class DispatchCollectionProofTest {
    CollectionGenerationInputTest f;
    Frozen frozen;
    SourceMemberEvidenceService members;
    CollectionGenerationInputTest fixture(){return new CollectionGenerationInputTest();}
    @BeforeEach void setup(){
        f=fixture();f.setup();var p=f.handoff();var command=f.command(p,false);
        frozen=f.flow(command,f.store(command),f.proofs::read).prepare(command,f.action("fictional-dispatch-prepare"));
        members=mock(SourceMemberEvidenceService.class);
        when(members.read(anySet(),any())).thenReturn(List.of(new SourceMemberEvidenceService.Fact(
                "fictional-item",2,"news_tech","fictional-canonical-identity","Fictional original title",null,"{}")));
    }
    DispatchReadinessService service(CollectionProofSource collections){
        var db=f.fixture.f;var inputs=new JdbcFrozenInputs(db.source);
        return new DispatchReadinessService(new JdbcDispatchInputs(db.source),inputs,
                new FrozenDependencyIndexService(inputs,new JdbcFrozenDependencies(db.source)),
                new GenerationLineageService(new JdbcGenerationLineage(db.source),db.clock,"fictional-worker"),
                mock(ContentReadService.class),members,target->true,collections);
    }
    boolean ready(DispatchReadinessService service){return f.fixture.f.writes.execute(
            new SharedWriteExecutor.ActionContext("fictional-dispatch-check",frozen.ownerId(),null),
            k->null,a->service.ready(frozen.ownerId()));}
    void rewriteInput(UnaryOperator<ObjectNode> change){
        var db=f.fixture.f;
        db.writes.execute(new SharedWriteExecutor.ActionContext("fictional-corrupt-input",frozen.ownerId(),null),k->null,a->{
            var input=change.apply((ObjectNode)CanonicalJson.parse(frozen.snapshotJson()));
            db.jdbc.update("DELETE FROM jobs WHERE owner_job_id=? AND purpose='input'",frozen.ownerId());
            db.jdbc.update("UPDATE jobs SET input_snapshot_json=NULL WHERE id=?",frozen.ownerId());
            new JdbcFrozenInputs(db.source).save(frozen.ownerId(),JdbcFrozenInputs.canonical(input),
                    CanonicalJson.hash(CanonicalJson.Domain.INPUT,input),db.clock.instant());return true;
        });
    }
    @Test void realOriginalCollectionIsReadyAndDeletedReceiptPreventsMemberLookup(){
        assertTrue(ready(service(f.proofs::read)));clearInvocations(members);
        var db=f.fixture.f;db.jdbc.update("DELETE FROM jobs WHERE target_type='collection_handoff'");
        int count=db.count("1=1");assertThrows(RuntimeException.class,()->ready(service(f.proofs::read)));
        verifyNoInteractions(members);assertEquals(count,db.count("1=1"));assertEquals(0,db.count("target_type='worker_send'"));
    }
    @Test void changedTerminalConfigurationAndMissingProviderCannotAuthorizeSend(){
        assertThrows(NullPointerException.class,()->service(null));
        var db=f.fixture.f;var input=new JdbcFrozenInputs(db.source);
        var legacy=new DispatchReadinessService(new JdbcDispatchInputs(db.source),input,
                new FrozenDependencyIndexService(input,new JdbcFrozenDependencies(db.source)),
                new GenerationLineageService(new JdbcGenerationLineage(db.source),db.clock,"fictional-worker"),
                mock(ContentReadService.class),members,target->true);
        assertThrows(RuntimeException.class,()->ready(legacy));
        db.jdbc.update("UPDATE source_runs SET config_snapshot_json='{\"fictional_changed\":true}'");
        assertThrows(RuntimeException.class,()->ready(service(f.proofs::read)));verifyNoInteractions(members);
    }
    @Test void rehashedMissingEmptyOrAlteredManifestCannotAuthorizeSend(){
        for(String operation:List.of("missing","empty","forged")){
            setup();rewriteInput(input->{
                if(operation.equals("missing"))input.remove(FrozenCollectionManifest.FIELD);
                else if(operation.equals("empty"))input.set(FrozenCollectionManifest.FIELD,FrozenCollectionManifest.create(List.of()));
                else ((ObjectNode)input.get(FrozenCollectionManifest.FIELD).get("handoffs").get(0)).put("payload_hash","e".repeat(64));
                return input;
            });
            assertThrows(RuntimeException.class,()->ready(service(f.proofs::read)));verifyNoInteractions(members);
        }
    }
    @Test void matchingProviderCannotBindTrialOtherSourceDateGenerationOrStageToOriginalRoot(){
        for(String field:List.of("activationStage","sourceId","businessDate","generation","category")){
            setup();var input=(ObjectNode)CanonicalJson.parse(frozen.snapshotJson());
            var facts=(ObjectNode)input.get(FrozenCollectionManifest.FIELD).get("handoffs").get(0).get("facts");
            switch(field){
                case "activationStage"->facts.put(field,"trial");
                case "sourceId"->facts.put(field,"fictional-other-source");
                case "businessDate"->facts.put(field,"2026-01-02");
                case "generation"->facts.put(field,2);
                case "category"->facts.put(field,"agent");
            }
            var forged=FrozenCollectionManifest.read(input.get(FrozenCollectionManifest.FIELD)).getFirst();
            rewriteInput(ignored->input);
            assertThrows(RuntimeException.class,()->ready(service(run->forged)));verifyNoInteractions(members);
        }
    }
}
