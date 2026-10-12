package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import com.lianxibu.site.repository.jobs.JdbcOwnerJobRecovery;
import com.lianxibu.site.repository.jobs.JdbcResultState;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Owner original-input recovery. Capture remains outside, original proofs revalidate inside the sole writer. */
public final class OwnerJobRecoverService {
    private final SharedWriteExecutor writes;private final JdbcOwnerJobRecovery store;private final DispatchReadinessService readiness;private final TransactionTemplate reads;private final Clock clock;
    public OwnerJobRecoverService(SharedWriteExecutor writes,JdbcOwnerJobRecovery store,DispatchReadinessService readiness,PlatformTransactionManager tx,Clock clock){this.writes=Objects.requireNonNull(writes);this.store=Objects.requireNonNull(store);this.readiness=Objects.requireNonNull(readiness);this.clock=Objects.requireNonNull(clock);reads=new TransactionTemplate(Objects.requireNonNull(tx));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);}
    public JdbcOwnerJobRecovery.Recovered recover(Actor actor,String root,long revision,String reason,String requestKey){
        if(actor==null||actor.userId()==null||actor.userId().isBlank())throw new IdentityService.Rejected(IdentityService.Refusal.UNAUTHENTICATED,null);if(!"owner".equals(actor.role()))throw new IdentityService.Rejected(IdentityService.Refusal.FORBIDDEN,null);
        WireValues.id(root,false);WireValues.id(actor.userId(),false);if(revision<0||revision>=CanonicalJson.MAX_SAFE_INTEGER||reason==null||reason.isBlank()||reason.codePointCount(0,reason.length())>2048||requestKey==null||!requestKey.matches("[!-~]{1,128}"))throw new IllegalArgumentException("invalid original input recovery");
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("owner recovery requires transaction-free entry");
        var body=JsonMapper.shared().createObjectNode().put("root_id",root).put("expected_revision",revision).put("actor",actor.userId()).put("reason",reason);var command=new JdbcOwnerJobRecovery.Command(root,revision,actor.userId(),reason,JdbcResultState.sha(JdbcFrozenInputs.canonical(body)));String key=OwnerJobCancelService.actionKey(requestKey);
        var previous=reads.execute(s->{var result=store.committed(key,command);if(result==null)store.check(command);return result;});if(previous!=null)return previous;
        var captured=readiness.captureSource(root);
        return writes.execute(new SharedWriteExecutor.ActionContext(key,root,null),k->store.committed(k,command),c->store.recover(c.actionKey(),command,clock.instant().truncatedTo(ChronoUnit.MICROS),()->captured==null?readiness.ready(root):readiness.readyCaptured(root,captured)));
    }
}
