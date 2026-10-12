package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import com.lianxibu.site.repository.jobs.JdbcOwnerJobCancellation;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import java.time.Clock;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;

/** Owner authorization, immutable command identity, and the sole short shared writer. */
public final class OwnerJobCancelService {
    private final SharedWriteExecutor writes;private final JdbcOwnerJobCancellation store;private final Clock clock;private final OwnerAdHocIntentCancelService intents;
    public OwnerJobCancelService(SharedWriteExecutor writes,JdbcOwnerJobCancellation store,Clock clock){this(writes,store,clock,null);}
    public OwnerJobCancelService(SharedWriteExecutor writes,JdbcOwnerJobCancellation store,Clock clock,OwnerAdHocIntentCancelService intents){this.intents=intents;this.writes=Objects.requireNonNull(writes);this.store=Objects.requireNonNull(store);this.clock=Objects.requireNonNull(clock);}
    public JdbcOwnerJobCancellation.Cancelled cancel(Actor actor,String root,long expectedRevision,String reason,String requestKey){
        if(actor==null||actor.userId()==null||actor.userId().isBlank())throw new IdentityService.Rejected(IdentityService.Refusal.UNAUTHENTICATED,null);if(!"owner".equals(actor.role()))throw new IdentityService.Rejected(IdentityService.Refusal.FORBIDDEN,null);
        WireValues.id(root,false);WireValues.id(actor.userId(),false);if(expectedRevision<0||expectedRevision>CanonicalJson.MAX_SAFE_INTEGER||reason==null||reason.isBlank()||reason.codePointCount(0,reason.length())>2048||requestKey==null||!requestKey.matches("[!-~]{1,128}"))throw new IllegalArgumentException("invalid cancellation command");
        if(intents!=null){var original=intents.cancelIfIntent(actor,root,expectedRevision,reason,requestKey);if(original!=null)return original;}
        var body=JsonMapper.shared().createObjectNode().put("root_id",root).put("expected_revision",expectedRevision).put("actor",actor.userId()).put("reason",reason);String hash=JdbcResultHash.command(body),key=actionKey(requestKey);
        var command=new JdbcOwnerJobCancellation.Command(root,expectedRevision,actor.userId(),reason,hash);return writes.execute(new SharedWriteExecutor.ActionContext(key,root,null),k->store.committed(k,command),context->store.cancel(context.actionKey(),command,clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS)));
    }
    public static String actionKey(String key){return JdbcFrozenInputs.stableId("owner-job-action",key);}
    private static final class JdbcResultHash{static String command(tools.jackson.databind.JsonNode body){return com.lianxibu.site.repository.jobs.JdbcResultState.sha(JdbcFrozenInputs.canonical(body));}}
}
