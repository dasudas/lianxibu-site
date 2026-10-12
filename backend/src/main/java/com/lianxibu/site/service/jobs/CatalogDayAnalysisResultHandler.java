package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import java.util.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import tools.jackson.databind.JsonNode;

/** Whole-day analysis authority around the existing content/body and entity-state transaction. */
public final class CatalogDayAnalysisResultHandler implements ResultApplicationService.Handler {
    private final ResultApplicationService.Handler delegate;private final JdbcFrozenInputs inputs;
    private final Map<String,CatalogDayAnalysisResultPermitService> permits;private final TransactionTemplate reads;
    public CatalogDayAnalysisResultHandler(ResultApplicationService.Handler delegate,JdbcFrozenInputs inputs,
            PlatformTransactionManager manager,Map<String,CatalogDayAnalysisResultPermitService> permits){
        this.delegate=Objects.requireNonNull(delegate);this.inputs=Objects.requireNonNull(inputs);
        if(permits!=null&&!permits.keySet().equals(Set.of("agent","skill","workflow","model")))throw invalid();this.permits=permits==null?null:Map.copyOf(permits);
        reads=new TransactionTemplate(Objects.requireNonNull(manager));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    @Override public ResultApplicationService.Handler prepare(ResultApplicationService.Input input,ResultApplicationService.Validated result){return capture(input,false,delegate.prepare(input,result));}
    @Override public ResultApplicationService.Handler prepareManual(ResultApplicationService.Input input){return capture(input,true,delegate.prepareManual(input));}
    private ResultApplicationService.Handler capture(ResultApplicationService.Input input,boolean manual,ResultApplicationService.Handler actual){
        if(TransactionSynchronizationManager.isActualTransactionActive()||input==null||input.stage()==null||!input.stage().analysis()||actual==null)throw invalid();
        var snapshot=Objects.requireNonNull(reads.execute(s->CanonicalJson.parse(inputs.read(input.rootId(),input.inputHash()))));
        if(!snapshot.has(CatalogAnalysisInputProfile.FIELD)){if(permits!=null)throw invalid();return actual;}
        if(permits==null)throw invalid();var profile=CatalogAnalysisInputProfile.complete(snapshot);var context=profile.command().applicationContext();
        if(!input.equals(context.input(input.rootId(),input.attemptId(),input.inputHash(),input.expectedRootRevision())))throw invalid();
        var provider=permits.get(context.contentKind());if(provider==null)throw invalid();var captured=provider.capture(input,manual);
        return (value,result,current,mode)->{provider.verify(captured,value,mode);return actual.apply(value,result,current,mode);};
    }
    /** A caller cannot bypass the preparation phase. */
    @Override public ResultApplicationService.Applied apply(ResultApplicationService.Input input,ResultApplicationService.Validated result,ResultApplicationService.Current current,boolean manual){throw invalid();}
    private static ResultApplicationService.Rejected invalid(){return new ResultApplicationService.Rejected();}
}
