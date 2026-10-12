package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.repository.jobs.JdbcOwnerJobClosureGuard;
import com.lianxibu.site.repository.jobs.JdbcResultState;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.jobs.JobsService.*;
import com.lianxibu.site.service.jobs.UnknownCallRecoveryService.*;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** Binds the existing durable unknown-closure protocol to the exact owner URL root in every transaction. */
public final class OwnerJobCloseUnresolvedService {
    public record Closed(String rootId,long revision,String operationRef,Result result){ }
    private final SharedWriteExecutor writes;private final JdbcOwnerJobClosureGuard guard;private final UnknownCallJdbcStore store;private final Clock clock;
    public OwnerJobCloseUnresolvedService(SharedWriteExecutor writes,JdbcOwnerJobClosureGuard guard,UnknownCallJdbcStore store,Clock clock){this.writes=Objects.requireNonNull(writes);this.guard=Objects.requireNonNull(guard);this.store=Objects.requireNonNull(store);this.clock=Objects.requireNonNull(clock);}
    public Closed close(Actor actor,String root,String attempt,long expectedRevision,String reason,String requestKey){
        if(actor==null||actor.userId()==null||actor.userId().isBlank())throw new IdentityService.Rejected(IdentityService.Refusal.UNAUTHENTICATED,null);if(!"owner".equals(actor.role()))throw new IdentityService.Rejected(IdentityService.Refusal.FORBIDDEN,null);
        WireValues.id(root,false);WireValues.id(attempt,false);WireValues.id(actor.userId(),false);if(expectedRevision<0||expectedRevision>=CanonicalJson.MAX_SAFE_INTEGER||requestKey==null||!requestKey.matches("[!-~]{1,128}"))throw new IllegalArgumentException("invalid unknown closure command");
        String key=OwnerJobCancelService.actionKey(requestKey);var binding=new Bound(root,attempt,expectedRevision);
        try{var result=new UnknownCallRecoveryService(writes,binding,clock).closeUnresolved(attempt,expectedRevision,actor.userId(),reason,true,new SharedWriteExecutor.ActionContext(key,root,null));return new Closed(root,expectedRevision+1,"recovery-"+JdbcResultState.sha(key).substring(0,55),result);}
        catch(UnknownCallRecoveryService.Rejected refused){throw new JobRejected(JobRefusal.RUN_FROZEN);}
    }
    private final class Bound implements Store {
        final String root,attempt;final long revision;
        Bound(String root,String attempt,long revision){this.root=root;this.attempt=attempt;this.revision=revision;}
        JdbcOwnerJobClosureGuard.Root root(){var value=guard.root(root);if(value==null)throw new JobRejected(JobRefusal.NOT_FOUND);if(!"logical_task".equals(value.kind())||!Integer.valueOf(3).equals(value.maximum()))throw new JobRejected(JobRefusal.RUN_FROZEN);return value;}
        Attempt bound(String id){root();var value=store.attempt(id);if(value==null||!attempt.equals(id)||!attempt.equals(value.attemptId())||!root.equals(value.rootId()))throw new JobRejected(JobRefusal.RUN_FROZEN);return value;}
        @Override public Result committedRelease(String key,String hash){bound(attempt);var result=store.committedRelease(key,hash);if(result!=null&&(result.state()!=State.CLOSED_UNRESOLVED||result.callCount()<1||result.callCount()>3||result.callCount()!=root().calls()||!"unknown".equals(result.usageState())||!"closed_unresolved".equals(result.reason())))throw new JobRejected(JobRefusal.IDENTITY_CONFLICT);return result;}
        @Override public Attempt attempt(String id){var value=bound(id);if(value.rootRevision()!=revision)throw new JobRejected(JobRefusal.REVISION_CONFLICT);return value;}
        @Override public Result closeUnresolved(Attempt value,long expected,String operator,String reason,Instant now,String key,String hash){var current=bound(value.attemptId());if(!value.equals(current)||expected!=revision||current.rootRevision()!=revision)throw new JobRejected(JobRefusal.REVISION_CONFLICT);return store.closeUnresolved(current,expected,operator,reason,now,key,hash);}
        @Override public Boolean committedLateEvidence(String key,String hash){throw unsupported();}
        @Override public boolean stoppedOwner(String epoch){throw unsupported();}
        @Override public Result releaseUnknown(Attempt value,Cause cause,Instant now,String key,String hash){throw unsupported();}
        @Override public Boolean appendLate(Attempt value,LateEvidence evidence,Instant now,String key,String hash){throw unsupported();}
        private IllegalStateException unsupported(){return new IllegalStateException("owner closure operation required");}
    }
}
