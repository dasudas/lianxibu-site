package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.jobs.JobsService.*;
import java.util.Objects;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Owner confirmation consumes original permanent input and result, with no new provider request. */
public final class OwnerJobApplyResultService {
    @FunctionalInterface public interface ManualApplications {
        AttemptOutcomeService.Application forManual(WorkerAuthorizationService.Identity identity,Actor actor);
    }
    public static final class InvalidConfirmation extends IllegalArgumentException {private InvalidConfirmation(){super("content revision must match original result kind");}}
    public record Applied(String operationRef,String rootId,long revision,String contentId,Long contentRevision){ }
    private final JdbcOwnerRetainedResultReads original;
    private final TransactionTemplate reads;
    private final ManualApplications applications;
    public OwnerJobApplyResultService(JdbcOwnerRetainedResultReads original,PlatformTransactionManager tx,ManualApplications applications){
        this.original=Objects.requireNonNull(original);this.applications=Objects.requireNonNull(applications);
        reads=new TransactionTemplate(Objects.requireNonNull(tx));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public Applied apply(Actor actor,String root,long expectedRevision,long expectedContentRevision,String confirmedHash,String reason,String requestKey){
        return apply(actor,root,expectedRevision,Long.valueOf(expectedContentRevision),confirmedHash,reason,requestKey);
    }
    public Applied apply(Actor actor,String root,long expectedRevision,Long expectedContentRevision,String confirmedHash,String reason,String requestKey){
        if(actor==null||actor.userId()==null||actor.userId().isBlank())throw new IdentityService.Rejected(IdentityService.Refusal.UNAUTHENTICATED,null);
        if(!"owner".equals(actor.role()))throw new IdentityService.Rejected(IdentityService.Refusal.FORBIDDEN,null);
        WireValues.id(root,false);WireValues.id(actor.userId(),false);
        if(expectedRevision<0||expectedRevision>=CanonicalJson.MAX_SAFE_INTEGER||(expectedContentRevision!=null&&(expectedContentRevision<0||expectedContentRevision>=CanonicalJson.MAX_SAFE_INTEGER))
                ||confirmedHash==null||!confirmedHash.matches("[0-9a-f]{64}")||reason==null||reason.isBlank()||reason.codePointCount(0,reason.length())>2048
                ||requestKey==null||!requestKey.matches("[!-~]{1,128}"))throw new IllegalArgumentException("invalid owner result confirmation");
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("owner application must own its transaction");
        var frame=reads.execute(s->original.read(root));if(frame==null)throw new JobRejected(JobRefusal.NOT_FOUND);
        if(frame.resultHash()==null||frame.identity()==null)throw new JobRejected(JobRefusal.RUN_FROZEN);
        var id=frame.identity();var identity=new WorkerAuthorizationService.Identity(id.rootId(),id.attemptId(),id.dispatchToken(),id.slotRevision(),id.fencing(),id.leaseUntil(),id.frozenRequestJson(),id.inputHash());
        var context=FrozenApplicationContext.fromJson(CanonicalJson.parse(frame.inputJson()).get(FrozenApplicationContext.FIELD));
        boolean contentResult=context.stage().analysis()||context.stage().report();
        if(contentResult!=(expectedContentRevision!=null))throw new InvalidConfirmation();
        String key=OwnerJobCancelService.actionKey(requestKey);
        try{
            var application=Objects.requireNonNull(applications.forManual(identity,actor));var input=Objects.requireNonNull(application.input());
            if(!root.equals(input.rootId())||!id.attemptId().equals(input.attemptId())||!frame.inputHash().equals(input.inputHash())||frame.generation()!=input.generation())throw new IllegalStateException("original manual application input unavailable");
            var result=application.service().applyManual(input,confirmedHash,expectedRevision,expectedContentRevision,reason,true,new SharedWriteExecutor.ActionContext(key,root,frame.inputHash()));
            if(result==null||result.status()!=ResultApplicationService.Status.APPLIED||!confirmedHash.equals(result.resultHash())||!Objects.equals(context.contentId(),result.contentId())
                    ||(contentResult?(result.contentRevision()==null||result.contentRevision()!=expectedContentRevision+1):result.contentRevision()!=null))throw new IllegalStateException("original manual application receipt unavailable");
            return new Applied(JdbcFrozenInputs.stableId("result-receipt",key),root,expectedRevision+1,result.contentId(),result.contentRevision());
        }catch(ResultApplicationService.ManualRejected rejected){throw new JobRejected(JobRefusal.valueOf(rejected.refusal().name()));}
    }
}
