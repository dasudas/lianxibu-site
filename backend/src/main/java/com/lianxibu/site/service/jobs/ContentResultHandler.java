package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.service.content.GeneratedContentService;
import com.lianxibu.site.service.jobs.ResultApplicationService.*;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Report/analysis result application; the frozen context supplier must verify retained input proof. */
public final class ContentResultHandler implements Handler {
    @FunctionalInterface public interface Downstream {
        /** Database-only work joining the same shared transaction, after the body CAS succeeded. */
        void afterApplied(Input input,FrozenApplicationContext context,Validated result,Applied applied);
    }
    private final GeneratedContentService contents;private final Supplier<FrozenApplicationContext> context;
    private final Downstream downstream;
    public ContentResultHandler(GeneratedContentService contents,Supplier<FrozenApplicationContext> context,Downstream downstream) {
        this.contents=Objects.requireNonNull(contents);this.context=Objects.requireNonNull(context);this.downstream=Objects.requireNonNull(downstream);
    }
    @Override public Applied apply(Input input,Validated result,Current current,boolean manual) {
        if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("content result requires the shared write transaction");
        var frozen=context.get();
        if(input==null||result==null||current==null||frozen==null||!frozen.referencesComplete()
                ||!input.equals(frozen.input(input.rootId(),input.attemptId(),input.inputHash(),input.expectedRootRevision()))
                ||!input.inputHash().equals(result.inputHash())||!current.dependenciesValid())throw new Rejected();
        String kind=input.stage().report()?"report":input.stage().analysis()?"analysis":null;
        if(kind==null||!kind.equals(result.kind())||frozen.contentKind()==null||frozen.contentId()==null)throw new Rejected();
        var payload=CanonicalJson.parse(result.payloadJson());
        if(!kind.equals(payload.path("kind").asString())||payload.size()!=2||payload.get("body")==null)throw new Rejected();
        var applied=contents.write(new GeneratedContentService.Write(frozen.contentKind(),frozen.contentId(),
                manual?current.contentRevision():input.expectedContentRevision(),input.rootId(),
                new String(CanonicalJson.bytes(payload.get("body")),StandardCharsets.UTF_8)));
        if(applied==null)return null;
        var value=new Applied(applied.id(),applied.revision());downstream.afterApplied(input,frozen,result,value);return value;
    }
}
