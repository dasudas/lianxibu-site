package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.service.jobs.ResultApplicationService.*;
import java.util.Objects;
import java.util.function.BiFunction;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Temporary answers remain in the parent's permanent result ledger, never in public content. */
public final class AdHocResultHandler implements Handler {
    private final BiFunction<String,String,String> snapshots;
    public AdHocResultHandler(BiFunction<String,String,String> snapshots){this.snapshots=Objects.requireNonNull(snapshots);}
    @Override public Applied apply(Input input,Validated result,Current current,boolean manual) {
        if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new IllegalStateException("temporary result requires shared write transaction");
        if(input==null||result==null||current==null||input.stage()!=GenerationFlowService.Stage.AD_HOC_AI||!"ad_hoc".equals(result.kind())||!current.dependenciesValid())throw new Rejected();
        var snapshot=CanonicalJson.parse(snapshots.apply(input.rootId(),input.inputHash()));
        if(!input.inputHash().equals(CanonicalJson.hash(CanonicalJson.Domain.INPUT,snapshot)))throw new Rejected();
        var context=FrozenApplicationContext.fromJson(snapshot.get(FrozenApplicationContext.FIELD));
        if(!context.referencesComplete()||!input.equals(context.input(input.rootId(),input.attemptId(),input.inputHash(),input.expectedRootRevision())))throw new Rejected();
        var envelope=JsonMapper.shared().createObjectNode();envelope.put("schema_version","1");envelope.put("input_hash",result.inputHash());envelope.put("result_hash",result.resultHash());envelope.set("payload",CanonicalJson.parse(result.payloadJson()));
        if(!result.equals(ResultApplicationService.validate(input,envelope.toString())))throw new Rejected();
        return new Applied(null,null);
    }
}
