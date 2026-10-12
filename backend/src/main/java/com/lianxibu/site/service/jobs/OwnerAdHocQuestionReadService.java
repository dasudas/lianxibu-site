package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.jobs.JobsService.*;
import java.time.Clock;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Owner original question and retained answer; no read can authorize execution. */
public final class OwnerAdHocQuestionReadService {
    public record View(Job intent,AdHocRequest originalRequest,Job preparedJob,ValidatedResult retainedResult) {
        @Override public String toString(){return "OwnerTemporaryQuestionView[redacted]";}
    }
    private final DataSource source;
    private final JdbcOwnerAdHocCreation originals;
    private final JdbcOwnerRetainedResultReads retained;
    private final OwnerJobsReadService jobs;
    private final JdbcTemplate sql;
    private final TransactionTemplate reads;
    public OwnerAdHocQuestionReadService(DataSource source,PlatformTransactionManager tx){
        this.source=Objects.requireNonNull(source);Objects.requireNonNull(tx);
        originals=new JdbcOwnerAdHocCreation(source);retained=new JdbcOwnerRetainedResultReads(source);sql=new JdbcTemplate(source);
        var views=new JdbcOwnerJobViews(source);jobs=new OwnerJobsReadService(views,new OwnerJobInputMetadataService(views,new JdbcFrozenInputs(source)),tx);
        reads=new TransactionTemplate(tx);reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public View get(Actor actor,String intentId){
        owner(actor);WireValues.id(intentId,false);
        try{
            if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();
            var accepted=reads.execute(s->originals.originalIntent(intentId));
            if(accepted==null)throw new IdentityService.Rejected(IdentityService.Refusal.NOT_FOUND,null);
            var plan=new OwnerAdHocInputBuilder().build(accepted);var command=accepted.command();
            var store=new GenerationJdbcStore(source,Clock.fixed(accepted.cutoff(),java.time.ZoneOffset.UTC),command.actor(),command.rootId(),plan.command());
            store.primePureInput(plan.frozen());
            return reads.execute(s->{
                var fresh=originals.originalIntent(intentId);if(fresh==null||!accepted.json().equals(fresh.json()))throw invalid();
                var frozen=store.committed("owner-ad-hoc-prepare:"+intentId);
                var related=sql.query("SELECT id,record_kind FROM jobs WHERE id=? OR (record_kind='logical_task' AND intent_id=?) OR (record_kind='ai_attempt' AND root_job_id=?) LIMIT 5",(r,n)->new String[]{r.getString(1),r.getString(2)},command.rootId(),intentId,command.rootId());
                if(frozen==null&&!related.isEmpty()||frozen!=null&&!frozen.equals(plan.frozen()))throw invalid();
                Job intent=jobs.get(actor,intentId);if(!"generation_intent".equals(intent.recordKind())||!"ad_hoc_ai".equals(intent.taskType())||!intentId.equals(intent.intentId()))throw invalid();
                if(frozen==null)return new View(intent,new AdHocRequest(command.prompt()),null,null);
                if(related.size()<2||related.size()>4||related.stream().filter(r->"logical_task".equals(r[1])).count()!=1||related.stream().anyMatch(r->!Set.of("logical_task","ai_attempt").contains(r[1])))throw invalid();
                var frame=retained.read(command.rootId());if(frame==null||frame.generation()!=1||frame.latestGeneration()!=1||!frozen.inputHash().equals(frame.inputHash()))throw invalid();
                Job root=jobs.get(actor,command.rootId());if(!intentId.equals(root.intentId())||!"ad_hoc_ai".equals(root.taskType())||root.priority()!=14||root.contentId()!=null||root.reportMode()!=null||!command.rootId().equals(root.inputSnapshotId())||!accepted.cutoff().equals(root.inputCutoffAt()))throw invalid();
                ValidatedResult result=null;
                if(frame.resultHash()!=null){
                    if(!frozen.snapshotJson().equals(frame.inputJson())||!frozen.requestJson().equals(JdbcFrozenInputs.canonical(CanonicalJson.parse(frame.identity().frozenRequestJson()))))throw invalid();
                    var body=CanonicalJson.parse(frame.inputJson());var context=FrozenApplicationContext.fromJson(body.get(FrozenApplicationContext.FIELD));
                    if(context.stage()!=GenerationFlowService.Stage.AD_HOC_AI||context.generation()!=1||context.contentId()!=null||!context.referencesComplete())throw invalid();
                    var input=context.input(command.rootId(),frame.identity().attemptId(),frame.inputHash(),root.revision());
                    var checked=ResultApplicationService.validate(input,frame.envelope());
                    if(!frame.resultHash().equals(checked.resultHash())||!"ad_hoc".equals(checked.kind()))throw invalid();
                    result=new ValidatedResult("1",checked.inputHash(),CanonicalJson.parse(checked.payloadJson()),checked.resultHash());
                }
                return new View(intent,new AdHocRequest(command.prompt()),root,result);
            });
        }catch(IdentityService.Rejected refused){throw refused;}catch(RuntimeException unavailable){throw new IdentityService.Rejected(IdentityService.Refusal.TEMPORARILY_UNAVAILABLE,"1");}
    }
    private static void owner(Actor actor){if(actor==null||actor.userId()==null||actor.userId().isBlank())throw new IdentityService.Rejected(IdentityService.Refusal.UNAUTHENTICATED,null);if(!"owner".equals(actor.role()))throw new IdentityService.Rejected(IdentityService.Refusal.FORBIDDEN,null);}
    private static IllegalStateException invalid(){return new IllegalStateException("original temporary question unavailable");}
}
