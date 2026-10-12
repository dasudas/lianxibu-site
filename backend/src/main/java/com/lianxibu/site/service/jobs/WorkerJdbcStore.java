package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.JdbcJobRecords;
import com.lianxibu.site.repository.jobs.JdbcWorkerState;
import com.lianxibu.site.service.jobs.WorkerAuthorizationService.*;
import java.nio.charset.StandardCharsets;import java.security.MessageDigest;
import java.time.*;import java.util.*;import java.util.function.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;import tools.jackson.databind.json.JsonMapper;import tools.jackson.databind.node.ObjectNode;

/** Persistent worker port. Installing this adapter alone does not enable dispatch. */
public final class WorkerJdbcStore implements Store {
    private static final java.time.format.DateTimeFormatter MICROS=new java.time.format.DateTimeFormatterBuilder().appendInstant(6).toFormatter();
    private final JdbcWorkerState state;private final JdbcJobRecords records;
    private final String epoch;private final BooleanSupplier paused;private final Predicate<String> ready;private final Clock clock;private final DispatchReadinessService readiness;
    private final TransactionTemplate reads;private final LongPredicate choose;
    public WorkerJdbcStore(JdbcWorkerState state,JdbcJobRecords records,String epoch,BooleanSupplier paused,Predicate<String> ready,Clock clock){
        this(state,records,epoch,paused,ready,clock,null,null,WorkerJdbcStore::randomChoice);
    }
    /** Actual proof capture is explicit; constructing this port does not enable dispatch. */
    public WorkerJdbcStore(JdbcWorkerState state,JdbcJobRecords records,String epoch,BooleanSupplier paused,DispatchReadinessService readiness,Clock clock){
        this(state,records,epoch,paused,Objects.requireNonNull(readiness)::ready,clock,readiness,null,WorkerJdbcStore::randomChoice);
    }
    /** Explicit outside candidate scanning and final reservation proof. */
    public WorkerJdbcStore(JdbcWorkerState state,JdbcJobRecords records,String epoch,BooleanSupplier paused,DispatchReadinessService readiness,Clock clock,PlatformTransactionManager tx){
        this(state,records,epoch,paused,readiness,clock,tx,WorkerJdbcStore::randomChoice);
    }
    WorkerJdbcStore(JdbcWorkerState state,JdbcJobRecords records,String epoch,BooleanSupplier paused,DispatchReadinessService readiness,Clock clock,PlatformTransactionManager tx,LongPredicate choose){
        this(state,records,epoch,paused,Objects.requireNonNull(readiness)::ready,clock,readiness,Objects.requireNonNull(tx),choose);
    }
    private WorkerJdbcStore(JdbcWorkerState state,JdbcJobRecords records,String epoch,BooleanSupplier paused,Predicate<String> ready,Clock clock,DispatchReadinessService readiness,PlatformTransactionManager tx,LongPredicate choose){
        if(state==null||records==null||epoch==null||!epoch.matches("[A-Za-z0-9._:-]{1,64}")||paused==null||ready==null||clock==null||choose==null)throw new IllegalArgumentException("worker persistence dependencies required");
        this.state=state;this.records=records;this.epoch=epoch;this.paused=paused;this.ready=ready;this.clock=clock;this.readiness=readiness;
        this.choose=choose;reads=tx==null?null:new TransactionTemplate(tx);if(reads!=null){reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);}
    }
    @Override public Store captureClaim(SharedWriteExecutor.ActionContext action,Instant now){
        if(reads==null)return this;
        if(TransactionSynchronizationManager.isActualTransactionActive()||action==null||now==null)throw conflict();
        if(reads.execute(s->committedClaim(action.actionKey()))!=null)return new ClaimScope(action.actionKey(),now,null,null);
        if(!WorkerAuthorizationService.inWindow(clock.instant())||reads.execute(s->paused()||!slotFree()))return new ClaimScope(action.actionKey(),now,null,null);
        JdbcWorkerState.Prepared selected=null;JdbcWorkerState.CandidateCursor cursor=null;long count=0;boolean done=false;
        do{
            if(!WorkerAuthorizationService.inWindow(clock.instant())||paused())return new ClaimScope(action.actionKey(),now,null,null);
            var position=cursor;var page=reads.execute(s->state.candidatePage(now,position,50));
            for(var item:page.rows()){
                if(selected!=null&&item.priority()>selected.priority()){done=true;break;}
                if(!physicalCandidate(item)||!reads.execute(s->readiness.preflight(item.root())))continue;
                if(count==Long.MAX_VALUE)throw new GenerationFlowService.CapacityGuarded();count++;
                if(selected==null||choose.test(count))selected=item;
            }
            cursor=page.nextCursor();
        }while(!done&&cursor!=null);
        return new ClaimScope(action.actionKey(),now,selected,selected==null?null:readiness.captureSource(selected.root()));
    }
    private final class ClaimScope implements Store {
        private final String actionKey;private final Instant originalNow;private final JdbcWorkerState.Prepared original;private final DispatchReadinessService.Captured proof;
        private ClaimScope(String actionKey,Instant now,JdbcWorkerState.Prepared original,DispatchReadinessService.Captured proof){this.actionKey=actionKey;originalNow=now;this.original=original;this.proof=proof;}
        @Override public Identity committedClaim(String key){return WorkerJdbcStore.this.committedClaim(key);}
        @Override public Authorization committedAuthorization(String key){return WorkerJdbcStore.this.committedAuthorization(key);}
        @Override public Boolean committedAbort(String key){return WorkerJdbcStore.this.committedAbort(key);}
        @Override public boolean paused(){return WorkerJdbcStore.this.paused();}
        @Override public boolean slotFree(){return WorkerJdbcStore.this.slotFree();}
        @Override public List<Candidate> candidates(Instant now){
            com.lianxibu.site.repository.jobs.JdbcFrozenInputs.requireTx();if(!originalNow.equals(now))throw conflict();if(original==null)return List.of();
            var actual=state.candidate(original.root(),original.attempt(),now);requireOriginal(actual);return List.of(candidate(actual,true));
        }
        @Override public Identity reserve(Candidate chosen,String hint,Instant lease,Instant now,String key){
            if(original==null||!actionKey.equals(key)||!originalNow.equals(now)||!candidate(original,true).equals(chosen))throw conflict();
            var actual=selected(chosen,lease,now);requireOriginal(actual);
            if(!rawDependencyReady(actual)||!(proof==null?readiness.ready(actual.root()):readiness.readyCaptured(actual.root(),proof)))throw conflict();
            return reserveSelected(actual,lease,now,key);
        }
        private void requireOriginal(JdbcWorkerState.Prepared actual){if(actual==null||!original.equals(actual))throw conflict();}
        @Override public Authorization authorize(Identity identity,Instant now,String key){throw conflict();}
        @Override public Boolean refundNotSent(Identity identity,Instant eligible,String reason,String key){return WorkerJdbcStore.this.refundNotSent(identity,eligible,reason,key);}
        @Override public String toString(){return "WorkerClaimScope[redacted]";}
    }
    @Override public Store captureAuthorization(Identity identity){
        if(readiness==null)return this;
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()||identity==null)throw conflict();
        return new AuthorizationScope(identity,readiness.captureSource(identity.rootId()));
    }
    private final class AuthorizationScope implements Store {
        private final Identity original;private final DispatchReadinessService.Captured proof;
        private AuthorizationScope(Identity original,DispatchReadinessService.Captured proof){this.original=original;this.proof=proof;}
        @Override public Identity committedClaim(String key){return WorkerJdbcStore.this.committedClaim(key);}
        @Override public Authorization committedAuthorization(String key){return WorkerJdbcStore.this.committedAuthorization(key);}
        @Override public Boolean committedAbort(String key){return WorkerJdbcStore.this.committedAbort(key);}
        @Override public boolean paused(){return WorkerJdbcStore.this.paused();}
        @Override public boolean slotFree(){return WorkerJdbcStore.this.slotFree();}
        @Override public List<Candidate> candidates(Instant now){throw conflict();}
        @Override public Identity reserve(Candidate chosen,String hint,Instant lease,Instant now,String key){throw conflict();}
        @Override public Authorization authorize(Identity identity,Instant now,String key){
            com.lianxibu.site.repository.jobs.JdbcFrozenInputs.requireTx();if(!original.equals(identity))throw conflict();
            return authorizeInternal(identity,now,key,()->proof==null?readiness.ready(identity.rootId()):readiness.readyCaptured(identity.rootId(),proof));
        }
        @Override public Boolean refundNotSent(Identity identity,Instant eligible,String reason,String key){return WorkerJdbcStore.this.refundNotSent(identity,eligible,reason,key);}
        @Override public String toString(){return "WorkerAuthorizationScope[redacted]";}
    }
    @Override public Identity committedClaim(String key){var receipt=receipt(key,"worker_claim");return receipt==null?null:identity(receipt);}
    @Override public Authorization committedAuthorization(String key){var receipt=receipt(key,"worker_send");return receipt==null?null:new Authorization(false,"already_authorized");}
    @Override public Boolean committedAbort(String key){var receipt=receipt(key,"worker_not_sent");return receipt==null?null:receipt.path("refunded").booleanValue();}
    @Override public boolean paused(){return paused.getAsBoolean();}
    @Override public boolean slotFree(){return "free".equals(state.slot().state());}
    @Override public List<Candidate> candidates(Instant now){return state.candidates(now).stream().map(p->new Candidate(p.root(),p.attempt(),p.priority(),p.eligible(),p.rootState(),p.attemptState(),dependencyReady(p),p.calls(),p.maximum(),p.request(),p.inputHash(),p.revision())).toList();}
    @Override public Identity reserve(Candidate chosen,String requestTokenHint,Instant lease,Instant now,String key){
        // The SQL token is unsigned numeric fencing. The random service hint is not a SQL identity.
        var selected=selected(chosen,lease,now);if(!dependencyReady(selected))throw conflict();return reserveSelected(selected,lease,now,key);
    }
    private JdbcWorkerState.Prepared selected(Candidate chosen,Instant lease,Instant now){
        if(chosen==null||now==null||lease==null||!lease.equals(now.plusSeconds(150))||paused())throw conflict();
        var actual=state.candidate(chosen.rootId(),chosen.attemptId(),now);
        if(actual==null||actual.revision()!=chosen.rootRevision()||actual.calls()!=chosen.callCount()||actual.maximum()!=3||actual.calls()<0||actual.calls()>=3||!Objects.equals(actual.inputHash(),chosen.inputHash())||!same(actual.request(),chosen.frozenRequestJson()))throw conflict();return actual;
    }
    private Identity reserveSelected(JdbcWorkerState.Prepared selected,Instant lease,Instant now,String key){
        var slot=state.reserve(selected,epoch,lease,now);
        var id=new Identity(selected.root(),selected.attempt(),Long.toString(slot.fencing()),slot.revision(),slot.fencing(),lease,selected.request(),selected.inputHash());
        var payload=encode(id);payload.put("root_revision",selected.revision()+1);payload.put("owner_epoch",epoch);payload.put("started_at",MICROS.format(now));
        append(key,"worker_claim",target(id),payload,"worker claim",now);return id;
    }
    @Override public Authorization authorize(Identity id,Instant now,String key){
        if(readiness!=null)throw conflict();return authorizeInternal(id,now,key,()->ready.test(id.rootId()));
    }
    private Authorization authorizeInternal(Identity id,Instant now,String key,BooleanSupplier inputReady){
        var claim=claim(id);if(claim==null||!owns(id,claim,now))return new Authorization(false,"lost_ownership");
        if(state.authorized(id.attemptId()))return new Authorization(false,"already_authorized");
        if(paused()||!inputReady.getAsBoolean())return new Authorization(false,"operational_guard");
        state.authorize(id.attemptId(),now);var payload=encode(id);payload.put("owner_epoch",epoch);payload.put("send_authorized_at",MICROS.format(now));append(key,"worker_send",target(id),payload,"send authorized",now);return new Authorization(true,null);
    }
    @Override public Boolean refundNotSent(Identity id,Instant eligible,String reason,String key){
        var claim=claim(id);Instant now=clock.instant();if(claim==null||!owns(id,claim,now))return false;
        state.refund(id.rootId(),id.attemptId(),numeric(id),id.slotRevision(),eligible,now,reason);
        var payload=encode(id);payload.put("refunded",true);payload.put("reason",reason);payload.put("next_eligible_at",MICROS.format(eligible));append(key,"worker_not_sent",target(id),payload,reason,now);return true;
    }
    private boolean dependencyReady(JdbcWorkerState.Prepared p){return ready.test(p.root())&&rawDependencyReady(p);}
    private static boolean rawDependencyReady(JdbcWorkerState.Prepared p){return p.dependency()==null||p.dependencyState()!=null&&Set.of("succeeded","complete","partial","empty_success").contains(p.dependencyState());}
    private static boolean physicalCandidate(JdbcWorkerState.Prepared p){return p.priority()>=1&&p.priority()<=14&&p.calls()>=0&&p.calls()<3&&p.maximum()==3&&p.inputHash()!=null&&p.inputHash().matches("[0-9a-f]{64}")&&rawDependencyReady(p)&&WorkerAuthorizationService.completeRequest(p.request(),p.inputHash());}
    private static Candidate candidate(JdbcWorkerState.Prepared p,boolean ready){return new Candidate(p.root(),p.attempt(),p.priority(),p.eligible(),p.rootState(),p.attemptState(),ready,p.calls(),p.maximum(),p.request(),p.inputHash(),p.revision());}
    private static boolean randomChoice(long count){return java.util.concurrent.ThreadLocalRandom.current().nextLong(count)==0;}
    private JsonNode claim(Identity id){String json=state.claimReceipt(hash(json(target(id))));return json==null?null:CanonicalJson.parse(json);}
    private boolean owns(Identity id,JsonNode claim,Instant now){return epoch.equals(claim.path("owner_epoch").asText())&&identity(claim).equals(id)&&state.owns(id.rootId(),id.attemptId(),epoch,numeric(id),id.slotRevision(),claim.path("root_revision").asLong(),now,id.frozenRequestJson(),id.inputHash());}
    private JsonNode receipt(String key,String type){String json=state.receipt(hash(key),type);return json==null?null:CanonicalJson.parse(json);}
    private void append(String key,String type,ObjectNode target,ObjectNode payload,String reason,Instant now){records.appendDisposition(new JdbcJobRecords.Disposition("worker-"+hash(key).substring(0,56),key,type,json(target),epoch,reason,json(payload)),now);}
    private static ObjectNode target(Identity id){var node=JsonMapper.shared().createObjectNode();node.put("root_id",id.rootId());node.put("attempt_id",id.attemptId());node.put("dispatch_token",numeric(id));node.put("slot_revision",id.slotRevision());return node;}
    private static ObjectNode encode(Identity id){var node=target(id);node.put("fencing",id.fencing());node.put("lease_until",MICROS.format(id.leaseUntil()));node.put("request_hash",CanonicalJson.hash(CanonicalJson.Domain.ACTION,CanonicalJson.parse(id.frozenRequestJson())));node.put("input_hash",id.inputHash());return node;}
    private Identity identity(JsonNode node){
        var attempt=records.byId(node.path("attempt_id").asText());
        if(attempt==null||!"ai_attempt".equals(attempt.kind())||!node.path("root_id").asText().equals(attempt.rootId())||attempt.requestJson()==null)throw conflict();
        var request=CanonicalJson.parse(attempt.requestJson());
        if(!node.path("input_hash").asText().equals(request.path("input_hash").asText()))throw conflict();
        if(node.has("request_hash")){if(!node.path("request_hash").asText().equals(CanonicalJson.hash(CanonicalJson.Domain.ACTION,request)))throw conflict();}
        else if(!node.has("request_json")||!same(node.path("request_json").asText(),attempt.requestJson()))throw conflict();
        return new Identity(node.path("root_id").asText(),node.path("attempt_id").asText(),Long.toString(node.path("dispatch_token").asLong()),node.path("slot_revision").asLong(),node.path("fencing").asLong(),Instant.parse(node.path("lease_until").asText()),attempt.requestJson(),node.path("input_hash").asText());
    }
    private static long numeric(Identity id){if(id==null||id.dispatchToken()==null||!id.dispatchToken().matches("[1-9][0-9]{0,15}"))throw conflict();long number=Long.parseLong(id.dispatchToken());if(number!=id.fencing()||number>CanonicalJson.MAX_SAFE_INTEGER)throw conflict();return number;}
    private static boolean same(String a,String b){return a!=null&&b!=null&&Arrays.equals(CanonicalJson.bytes(CanonicalJson.parse(a)),CanonicalJson.bytes(CanonicalJson.parse(b)));}
    private static String json(JsonNode node){return new String(CanonicalJson.bytes(node),StandardCharsets.UTF_8);}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static IllegalStateException conflict(){return new IllegalStateException("worker persistence conflict");}
}
