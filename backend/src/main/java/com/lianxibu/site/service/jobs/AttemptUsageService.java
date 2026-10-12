package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.JdbcAttemptUsage;
import com.lianxibu.site.repository.jobs.JdbcResultState;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Provider-reported attempt metadata. Missing usage and cost are never estimated. */
public final class AttemptUsageService {
    public record Metadata(String providerRequestId,Long promptTokens,Long completionTokens,Long totalTokens,Long cacheHitTokens) {
        @Override public String toString(){return "AttemptMetadata[redacted]";}
    }
    private final SharedWriteExecutor writes;
    private final JdbcAttemptUsage store;
    private final Clock clock;
    private final String owner;
    public AttemptUsageService(SharedWriteExecutor writes,JdbcAttemptUsage store,Clock clock,String owner) {
        this.writes=Objects.requireNonNull(writes);this.store=Objects.requireNonNull(store);this.clock=Objects.requireNonNull(clock);
        if(owner==null||!owner.matches("[A-Za-z0-9._:-]{1,64}"))throw invalid();this.owner=owner;
    }
    public boolean record(WorkerAuthorizationService.Identity identity,Metadata metadata,SharedWriteExecutor.ActionContext action) {
        Objects.requireNonNull(identity);Objects.requireNonNull(action);String json=metadata(metadata);
        var proof=new JdbcResultState.Identity(identity.rootId(),identity.attemptId(),identity.dispatchToken(),identity.slotRevision(),identity.fencing(),identity.leaseUntil(),identity.frozenRequestJson(),identity.inputHash());
        return writes.execute(action,key->store.committed(key,proof,owner,json),ctx->store.record(ctx.actionKey(),proof,owner,json,clock.instant().truncatedTo(ChronoUnit.MICROS)));
    }
    private static String metadata(Metadata m) {
        if(m==null)throw invalid();String id=m.providerRequestId();
        if(id!=null&&(id.isBlank()||id.codePointCount(0,id.length())>256||id.codePoints().anyMatch(c->c<32||c==127)))throw invalid();
        count(m.promptTokens());count(m.completionTokens());count(m.totalTokens());count(m.cacheHitTokens());
        if(m.promptTokens()!=null&&m.completionTokens()!=null&&m.totalTokens()!=null&&m.promptTokens()+m.completionTokens()!=m.totalTokens()
                ||m.cacheHitTokens()!=null&&m.promptTokens()!=null&&m.cacheHitTokens()>m.promptTokens())throw invalid();
        var value=JsonMapper.shared().createObjectNode();if(id==null)value.putNull("provider_request_id");else value.put("provider_request_id",id);
        var usage=value.putObject("usage");usage.put("state",m.promptTokens()==null&&m.completionTokens()==null&&m.totalTokens()==null&&m.cacheHitTokens()==null?"unknown":"reported");
        put(usage,"prompt_tokens",m.promptTokens());put(usage,"completion_tokens",m.completionTokens());put(usage,"total_tokens",m.totalTokens());put(usage,"prompt_cache_hit_tokens",m.cacheHitTokens());
        return new String(CanonicalJson.bytes(value),java.nio.charset.StandardCharsets.UTF_8);
    }
    private static void count(Long value){if(value!=null&&(value<0||value>CanonicalJson.MAX_SAFE_INTEGER))throw invalid();}
    private static void put(ObjectNode node,String key,Long value){if(value==null)node.putNull(key);else node.put(key,value);}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("provider usage metadata invalid");}
}
