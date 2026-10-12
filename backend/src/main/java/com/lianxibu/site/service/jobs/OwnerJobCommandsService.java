package com.lianxibu.site.service.jobs;

import com.lianxibu.site.repository.jobs.JdbcOwnerJobCancellation;
import com.lianxibu.site.repository.jobs.JdbcOwnerJobRecovery;
import com.lianxibu.site.repository.jobs.JdbcOwnerResultDiscard;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.jobs.JobsService.*;
import java.util.List;
import java.util.Objects;

/** Permanent owner root action consumers. Unimplemented command providers refuse explicitly. */
public final class OwnerJobCommandsService implements OwnerJobsService.Commands {
    private final OwnerJobCancelService cancellation;
    private final OwnerJobCloseUnresolvedService closure;
    private final OwnerJobRecoverService recovery;
    private final OwnerJobDiscardResultService discard;
    private final OwnerJobApplyResultService application;
    private final OwnerJobResolveExternalService resolution;
    private final OwnerAdHocCreateService creation;
    public static final class InvalidAdHocCreate extends IllegalArgumentException {private InvalidAdHocCreate(){super("invalid temporary question");}}
    public static final class InvalidExternalEvidence extends IllegalArgumentException {private InvalidExternalEvidence(){super("invalid external verification evidence");}}
    public OwnerJobCommandsService(OwnerJobCancelService cancellation){this(cancellation,null);}
    public OwnerJobCommandsService(OwnerJobCancelService cancellation,OwnerJobCloseUnresolvedService closure){this(cancellation,closure,null);}
    public OwnerJobCommandsService(OwnerJobCancelService cancellation,OwnerJobCloseUnresolvedService closure,OwnerJobRecoverService recovery){this(cancellation,closure,recovery,null);}
    public OwnerJobCommandsService(OwnerJobCancelService cancellation,OwnerJobCloseUnresolvedService closure,OwnerJobRecoverService recovery,OwnerJobDiscardResultService discard){this(cancellation,closure,recovery,discard,null);}
    public OwnerJobCommandsService(OwnerJobCancelService cancellation,OwnerJobCloseUnresolvedService closure,OwnerJobRecoverService recovery,OwnerJobDiscardResultService discard,OwnerJobApplyResultService application){this(cancellation,closure,recovery,discard,application,null);}
    public OwnerJobCommandsService(OwnerJobCancelService cancellation,OwnerJobCloseUnresolvedService closure,OwnerJobRecoverService recovery,OwnerJobDiscardResultService discard,OwnerJobApplyResultService application,OwnerJobResolveExternalService resolution){this(cancellation,closure,recovery,discard,application,resolution,null);}
    public OwnerJobCommandsService(OwnerJobCancelService cancellation,OwnerJobCloseUnresolvedService closure,OwnerJobRecoverService recovery,OwnerJobDiscardResultService discard,OwnerJobApplyResultService application,OwnerJobResolveExternalService resolution,OwnerAdHocCreateService creation){this.creation=creation;this.resolution=resolution;this.cancellation=Objects.requireNonNull(cancellation);this.closure=closure;this.recovery=recovery;this.discard=discard;this.application=application;}
    @Override public Job create(Actor actor,JobCreate command,String key,String csrf,String origin){
        authorize(actor,key,csrf,origin);if(command==null)throw new InvalidAdHocCreate();
        if(!"ad_hoc_ai".equals(command.taskType())){if(command.request()!=null)throw new InvalidAdHocCreate();throw unavailable();}
        if(command.request()==null||command.target()==null||!"ad_hoc_request".equals(command.target().kind())||command.target().packageId()!=null||command.expectedRevision()!=0)throw new InvalidAdHocCreate();
        try{OwnerAdHocCreateCommand.create(actor,command.target().id(),command.request().prompt(),command.reason());}catch(IllegalArgumentException rejected){throw new InvalidAdHocCreate();}
        if(creation==null)throw unavailable();
        try{return creation.create(actor,command.target().id(),command.request().prompt(),command.reason(),key).response();}
        catch(com.lianxibu.site.service.jobs.JdbcOwnerAdHocCreation.Rejected rejected){throw new JobRejected(switch(rejected.getMessage()){case "revision_conflict"->JobRefusal.REVISION_CONFLICT;case "input_unchanged"->JobRefusal.INPUT_UNCHANGED;default->JobRefusal.IDENTITY_CONFLICT;});}
        catch(IdentityService.Rejected rejected){throw rejected;}catch(RuntimeException rejected){throw unavailable();}
    }
    @Override public ActionResult action(Actor actor,String id,JobAction command,String key,String csrf,String origin){
        authorize(actor,key,csrf,origin);if(command==null)throw new IllegalArgumentException("job action required");
        boolean closing="close_unresolved".equals(command.action()),recovering="recover".equals(command.action()),discarding="discard_result".equals(command.action()),applying="apply_result".equals(command.action()),resolving="resolve_external".equals(command.action());if(!closing&&!recovering&&!discarding&&!applying&&!resolving&&!"cancel".equals(command.action()))throw unavailable();
        if(!closing&&!resolving&&command.attemptId()!=null||!applying&&(command.expectedContentRevision()!=null||command.resultHash()!=null)||!resolving&&(command.externalState()!=null||command.evidenceRefs()!=null||command.result()!=null||command.usage()!=null))throw new IllegalArgumentException("unexpected job action fields");
        if(closing&&(command.attemptId()==null||command.attemptId().isBlank()))throw new IllegalArgumentException("unknown attempt required");
        if(applying&&command.resultHash()==null)throw new IllegalArgumentException("confirmed result hash required");
        try{
            if(resolving){if(resolution==null)throw unavailable();com.lianxibu.site.repository.jobs.JdbcOwnerExternalResolution.Resolved resolved;try{resolved=resolution.resolve(actor,id,command,key);}catch(IllegalArgumentException refused){throw new InvalidExternalEvidence();}return result(resolved.operationRef(),resolved.rootId(),resolved.revision());}
            if(applying){if(application==null)throw unavailable();var applied=application.apply(actor,id,command.expectedRevision(),command.expectedContentRevision(),command.resultHash(),command.reason(),key);var root=new ResourceRef("job",applied.rootId(),null);return new ActionResult(applied.operationRef(),root,applied.revision(),null,null,null,applied.contentId()==null?List.of(root):List.of(root,new ResourceRef("content",applied.contentId(),null)),null);}
            if(closing){if(closure==null)throw unavailable();var result=closure.close(actor,id,command.attemptId(),command.expectedRevision(),command.reason(),key);return result(result.operationRef(),result.rootId(),result.revision());}
            if(recovering){if(recovery==null)throw unavailable();var result=recovery.recover(actor,id,command.expectedRevision(),command.reason(),key);return result(result.operationRef(),result.rootId(),result.revision());}
            if(discarding){if(discard==null)throw unavailable();var result=discard.discard(actor,id,command.expectedRevision(),command.reason(),key);return result(result.operationRef(),result.rootId(),result.revision());}
            var result=cancellation.cancel(actor,id,command.expectedRevision(),command.reason(),key);var ref=new ResourceRef("job",result.rootId(),null);return new ActionResult(result.operationRef(),ref,result.revision(),null,null,null,List.of(ref),null);
        }catch(JdbcOwnerJobCancellation.Rejected rejected){throw new JobRejected(switch(rejected.getMessage()){case "not_found"->JobRefusal.NOT_FOUND;case "revision_conflict"->JobRefusal.REVISION_CONFLICT;case "run_frozen"->JobRefusal.RUN_FROZEN;default->JobRefusal.IDENTITY_CONFLICT;});}
        catch(JdbcOwnerJobRecovery.Rejected rejected){throw new JobRejected(switch(rejected.getMessage()){case "not_found"->JobRefusal.NOT_FOUND;case "revision_conflict"->JobRefusal.REVISION_CONFLICT;case "run_frozen"->JobRefusal.RUN_FROZEN;case "retry_exhausted"->JobRefusal.RETRY_EXHAUSTED;case "external_result_unresolved"->JobRefusal.EXTERNAL_RESULT_UNRESOLVED;default->JobRefusal.IDENTITY_CONFLICT;});}
        catch(JdbcOwnerResultDiscard.Rejected rejected){throw new JobRejected(switch(rejected.getMessage()){case "not_found"->JobRefusal.NOT_FOUND;case "revision_conflict"->JobRefusal.REVISION_CONFLICT;case "run_frozen"->JobRefusal.RUN_FROZEN;default->JobRefusal.IDENTITY_CONFLICT;});}
        catch(InvalidExternalEvidence rejected){throw rejected;}
        catch(OwnerJobApplyResultService.InvalidConfirmation rejected){throw rejected;}
        catch(JobRejected rejected){throw rejected;}catch(IdentityService.Rejected rejected){throw rejected;}catch(RuntimeException unavailable){throw unavailable();}
    }
    private static ActionResult result(String operation,String root,long revision){var ref=new ResourceRef("job",root,null);return new ActionResult(operation,ref,revision,null,null,null,List.of(ref),null);}
    private static void authorize(Actor actor,String key,String csrf,String origin){if(actor==null||actor.userId()==null||actor.userId().isBlank())throw new IdentityService.Rejected(IdentityService.Refusal.UNAUTHENTICATED,null);if(!"owner".equals(actor.role())||key==null||!key.matches("[!-~]{1,128}")||csrf==null||csrf.isBlank()||origin==null||origin.isBlank())throw new IdentityService.Rejected(IdentityService.Refusal.FORBIDDEN,null);}
    private static IdentityService.Rejected unavailable(){return new IdentityService.Rejected(IdentityService.Refusal.TEMPORARILY_UNAVAILABLE,"1");}
}
