package com.lianxibu.site.service.jobs;

import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.jobs.OperationalRuleEngine.*;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Ops slice of the jobs API; other job use cases delegate to their separately assembled service. */
public final class OperationalJobsService implements JobsService {
    private final OperationalJdbcStore store;
    private final OperationalMonitoringService monitor;
    private final JobsService otherJobs;
    private final Clock clock;
    private final TransactionTemplate reads;
    public OperationalJobsService(OperationalJdbcStore store, OperationalMonitoringService monitor,
                                  JobsService otherJobs, PlatformTransactionManager tx, Clock clock) {
        if(store==null||monitor==null||tx==null||clock==null)throw new IllegalArgumentException("operational API dependencies required");
        this.store=store;this.monitor=monitor;this.otherJobs=otherJobs;this.clock=clock;
        reads=new TransactionTemplate(tx);reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    @Override public JobOrAlertPage list(Actor actor,String kind,String type,long page,int size) {
        owner(actor);
        if(!"disposition".equals(kind)||!"ops_rule".equals(type))return other().list(actor,kind,type,page,size);
        if(page<1||size<1||size>50)throw new IllegalArgumentException("invalid operational page");
        return reads.execute(ignored->{
            List<Alert> all=store.views().stream().filter(view->view.state().level()!=null || view.resolvedAt()!=null
                    && clock.instant().isBefore(view.resolvedAt().plusSeconds(604800)))
                    .map(this::alert).sorted(Comparator.comparingInt((Alert a)->"resolved".equals(a.state())?2:"critical".equals(a.level())?0:1)
                            .thenComparing(Alert::lastSeenAt,Comparator.reverseOrder()).thenComparing(Alert::alertId)).toList();
            long offset=Math.multiplyExact(page-1,size);
            List<Alert> rows=offset>=all.size()?List.of():all.subList((int)offset,Math.min((int)offset+size,all.size()));
            return new AlertPage(rows,page,size,all.size());
        });
    }
    @Override public Job get(Actor actor,String id){owner(actor);return other().get(actor,id);}
    @Override public Job create(Actor actor,JobCreate command,String key,String csrf,String origin){owner(actor);return other().create(actor,command,key,csrf,origin);}
    @Override public ActionResult action(Actor actor,String id,JobAction command,String key,String csrf,String origin) {
        owner(actor);
        if(command==null)throw new IllegalArgumentException("job action required");
        boolean ops="acknowledge_alert".equals(command.action())||"resume_capacity_guard".equals(command.action());
        if(!ops)return other().action(actor,id,command,key,csrf,origin);
        if(key==null||!key.matches("[!-~]{1,128}")||csrf==null||csrf.isBlank()||origin==null||origin.isBlank())
            throw new IdentityService.Rejected(IdentityService.Refusal.FORBIDDEN,null);
        if(command.attemptId()!=null||command.expectedContentRevision()!=null||command.resultHash()!=null
                ||command.externalState()!=null||command.evidenceRefs()!=null||command.result()!=null||command.usage()!=null)
            throw new IllegalArgumentException("unexpected operational action fields");
        Key target=store.key(id);if(target==null)throw new OpsRejected(OpsRefusal.NOT_FOUND);
        try {
            State result=monitor.act(actor,target,command.expectedRevision(),"acknowledge_alert".equals(command.action())
                    ?OperationalMonitoringService.Action.ACKNOWLEDGE:OperationalMonitoringService.Action.RESUME_CAPACITY,
                    command.reason(),null,new ActionContext(key,id,null));
            var ref=new ResourceRef("ops_rule",id,null);
            return new ActionResult(OperationalJdbcStore.operationId(key),ref,result.revision(),null,null,null,List.of(ref),null);
        } catch(OperationalMonitoringService.Rejected rejected) {
            throw new OpsRejected(switch(rejected.getMessage()) {
                case "revision_conflict"->OpsRefusal.REVISION_CONFLICT;
                case "recovery_conditions_unmet"->OpsRefusal.RECOVERY_CONDITION_UNMET;
                case "configuration_unknown"->OpsRefusal.CONFIGURATION_UNKNOWN;
                default->OpsRefusal.IDENTITY_CONFLICT;
            });
        } catch(OperationalJdbcStore.Conflict conflict){throw new OpsRejected(OpsRefusal.IDENTITY_CONFLICT);}
        catch(com.lianxibu.site.repository.jobs.JdbcOperationalSummaries.Conflict conflict){throw new OpsRejected(OpsRefusal.REVISION_CONFLICT);}
    }
    private Alert alert(OperationalJdbcStore.View view) {
        State s=view.state();boolean active=s.level()!=null;Level level=active?s.level():view.resolvedLevel();
        return new Alert(OperationalJdbcStore.alertId(s.key()),s.key().ruleId(),s.key().resourceKey(),s.revision(),
                level==Level.CRITICAL?"critical":"warning",active?"active":"resolved",s.firstSeenAt(),
                active?s.lastSeenAt():view.resolvedAt(),s.observedValue(),s.threshold(),s.acknowledged(),s.nextReminderAt());
    }
    private JobsService other(){if(otherJobs==null)throw new IdentityService.Rejected(IdentityService.Refusal.TEMPORARILY_UNAVAILABLE,null);return otherJobs;}
    private static void owner(Actor actor){
        if(actor==null||actor.userId()==null||actor.userId().isBlank())throw new IdentityService.Rejected(IdentityService.Refusal.UNAUTHENTICATED,null);
        if(!"owner".equals(actor.role()))throw new IdentityService.Rejected(IdentityService.Refusal.FORBIDDEN,null);
    }
}
