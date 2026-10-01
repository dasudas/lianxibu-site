package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.JdbcJobRecords;
import com.lianxibu.site.repository.jobs.JdbcWorkerState;
import com.lianxibu.site.service.jobs.WorkerAuthorizationService.*;
import java.nio.charset.StandardCharsets;import java.security.MessageDigest;
import java.time.*;import java.util.*;import java.util.function.*;
import tools.jackson.databind.JsonNode;import tools.jackson.databind.json.JsonMapper;import tools.jackson.databind.node.ObjectNode;

/** Persistent worker port. Installing this adapter alone does not enable dispatch. */
public final class WorkerJdbcStore implements Store {
    private final JdbcWorkerState state;private final JdbcJobRecords records;
    private final String epoch;private final BooleanSupplier paused;private final Predicate<String> ready;private final Clock clock;
    public WorkerJdbcStore(JdbcWorkerState state,JdbcJobRecords records,String epoch,BooleanSupplier paused,Predicate<String> ready,Clock clock){
        if(state==null||records==null||epoch==null||!epoch.matches("[A-Za-z0-9._:-]{1,64}")||paused==null||ready==null||clock==null)throw new IllegalArgumentException("worker persistence dependencies required");
        this.state=state;this.records=records;this.epoch=epoch;this.paused=paused;this.ready=ready;this.clock=clock;
    }
    @Override public Identity committedClaim(String key){var receipt=receipt(key,"worker_claim");return receipt==null?null:identity(receipt);}
    @Override public Authorization committedAuthorization(String key){var receipt=receipt(key,"worker_send");return receipt==null?null:new Authorization(false,"already_authorized");}
    @Override public Boolean committedAbort(String key){var receipt=receipt(key,"worker_not_sent");return receipt==null?null:receipt.path("refunded").booleanValue();}
    @Override public boolean paused(){return paused.getAsBoolean();}
    @Override public boolean slotFree(){return "free".equals(state.slot().state());}
    @Override public List<Candidate> candidates(Instant now){return state.candidates(now).stream().map(p->new Candidate(p.root(),p.attempt(),p.priority(),p.eligible(),p.rootState(),p.attemptState(),dependencyReady(p),p.calls(),p.maximum(),p.request(),p.inputHash(),p.revision())).toList();}
    @Override public Identity reserve(Candidate chosen,String requestTokenHint,Instant lease,Instant now,String key){
        // The SQL token is unsigned numeric fencing. The random service hint is not a SQL identity.
        if(chosen==null||lease==null||!lease.equals(now.plusSeconds(150))||paused())throw conflict();
        var selected=state.candidates(now).stream().filter(p->p.root().equals(chosen.rootId())&&p.attempt().equals(chosen.attemptId())&&p.revision()==chosen.rootRevision()&&p.calls()==chosen.callCount()&&p.maximum()==3&&p.calls()<3&&dependencyReady(p)&&p.inputHash().equals(chosen.inputHash())&&same(p.request(),chosen.frozenRequestJson())).findFirst().orElseThrow(WorkerJdbcStore::conflict);
        var slot=state.reserve(selected,epoch,lease,now);
        var id=new Identity(selected.root(),selected.attempt(),Long.toString(slot.fencing()),slot.revision(),slot.fencing(),lease,selected.request(),selected.inputHash());
        var payload=encode(id);payload.put("root_revision",selected.revision()+1);payload.put("owner_epoch",epoch);payload.put("started_at",now.toString());
        append(key,"worker_claim",target(id),payload,"worker claim",now);return id;
    }
    @Override public Authorization authorize(Identity id,Instant now,String key){
        var claim=claim(id);if(claim==null||!owns(id,claim,now))return new Authorization(false,"lost_ownership");
        if(state.authorized(id.attemptId()))return new Authorization(false,"already_authorized");
        if(paused()||!ready.test(id.rootId()))return new Authorization(false,"operational_guard");
        state.authorize(id.attemptId(),now);var payload=encode(id);payload.put("owner_epoch",epoch);payload.put("send_authorized_at",now.toString());append(key,"worker_send",target(id),payload,"send authorized",now);return new Authorization(true,null);
    }
    @Override public Boolean refundNotSent(Identity id,Instant eligible,String reason,String key){
        var claim=claim(id);Instant now=clock.instant();if(claim==null||!owns(id,claim,now))return false;
        state.refund(id.rootId(),id.attemptId(),numeric(id),id.slotRevision(),eligible,now,reason);
        var payload=encode(id);payload.put("refunded",true);payload.put("reason",reason);payload.put("next_eligible_at",eligible.toString());append(key,"worker_not_sent",target(id),payload,reason,now);return true;
    }
    private boolean dependencyReady(JdbcWorkerState.Prepared p){return ready.test(p.root())&&(p.dependency()==null||p.dependencyState()!=null&&Set.of("succeeded","complete","partial","empty_success").contains(p.dependencyState()));}
    private JsonNode claim(Identity id){String json=state.claimReceipt(hash(json(target(id))));return json==null?null:CanonicalJson.parse(json);}
    private boolean owns(Identity id,JsonNode claim,Instant now){return epoch.equals(claim.path("owner_epoch").asText())&&identity(claim).equals(id)&&state.owns(id.rootId(),id.attemptId(),epoch,numeric(id),id.slotRevision(),claim.path("root_revision").asLong(),now,id.frozenRequestJson(),id.inputHash());}
    private JsonNode receipt(String key,String type){String json=state.receipt(hash(key),type);return json==null?null:CanonicalJson.parse(json);}
    private void append(String key,String type,ObjectNode target,ObjectNode payload,String reason,Instant now){records.appendDisposition(new JdbcJobRecords.Disposition("worker-"+hash(key).substring(0,56),key,type,json(target),epoch,reason,json(payload)),now);}
    private static ObjectNode target(Identity id){var node=JsonMapper.shared().createObjectNode();node.put("root_id",id.rootId());node.put("attempt_id",id.attemptId());node.put("dispatch_token",numeric(id));node.put("slot_revision",id.slotRevision());return node;}
    private static ObjectNode encode(Identity id){var node=target(id);node.put("fencing",id.fencing());node.put("lease_until",id.leaseUntil().toString());node.put("request_json",id.frozenRequestJson());node.put("input_hash",id.inputHash());return node;}
    private static Identity identity(JsonNode node){return new Identity(node.path("root_id").asText(),node.path("attempt_id").asText(),Long.toString(node.path("dispatch_token").asLong()),node.path("slot_revision").asLong(),node.path("fencing").asLong(),Instant.parse(node.path("lease_until").asText()),node.path("request_json").asText(),node.path("input_hash").asText());}
    private static long numeric(Identity id){if(id==null||id.dispatchToken()==null||!id.dispatchToken().matches("[1-9][0-9]{0,15}"))throw conflict();long number=Long.parseLong(id.dispatchToken());if(number!=id.fencing()||number>CanonicalJson.MAX_SAFE_INTEGER)throw conflict();return number;}
    private static boolean same(String a,String b){return a!=null&&b!=null&&Arrays.equals(CanonicalJson.bytes(CanonicalJson.parse(a)),CanonicalJson.bytes(CanonicalJson.parse(b)));}
    private static String json(JsonNode node){return new String(CanonicalJson.bytes(node),StandardCharsets.UTF_8);}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static IllegalStateException conflict(){return new IllegalStateException("worker persistence conflict");}
}
