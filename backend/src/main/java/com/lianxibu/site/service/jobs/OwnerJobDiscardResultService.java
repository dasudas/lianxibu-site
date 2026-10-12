package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import com.lianxibu.site.repository.jobs.JdbcOwnerResultDiscard;
import com.lianxibu.site.repository.jobs.JdbcResultState;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;

/** Validates original input and retained result before a single durable owner abandonment. */
public final class OwnerJobDiscardResultService {
    private final SharedWriteExecutor writes;private final JdbcOwnerResultDiscard store;private final JdbcFrozenInputs inputs;private final Clock clock;
    public OwnerJobDiscardResultService(SharedWriteExecutor writes,JdbcOwnerResultDiscard store,JdbcFrozenInputs inputs,Clock clock){this.writes=Objects.requireNonNull(writes);this.store=Objects.requireNonNull(store);this.inputs=Objects.requireNonNull(inputs);this.clock=Objects.requireNonNull(clock);}
    public JdbcOwnerResultDiscard.Discarded discard(Actor actor,String root,long revision,String reason,String requestKey){
        if(actor==null||actor.userId()==null||actor.userId().isBlank())throw new IdentityService.Rejected(IdentityService.Refusal.UNAUTHENTICATED,null);if(!"owner".equals(actor.role()))throw new IdentityService.Rejected(IdentityService.Refusal.FORBIDDEN,null);
        WireValues.id(root,false);WireValues.id(actor.userId(),false);if(revision<0||revision>=CanonicalJson.MAX_SAFE_INTEGER||reason==null||reason.isBlank()||reason.codePointCount(0,reason.length())>2048||requestKey==null||!requestKey.matches("[!-~]{1,128}"))throw new IllegalArgumentException("invalid result abandonment command");
        var body=JsonMapper.shared().createObjectNode().put("root_id",root).put("expected_revision",revision).put("actor",actor.userId()).put("reason",reason);var command=new JdbcOwnerResultDiscard.Command(root,revision,actor.userId(),reason,JdbcResultState.sha(JdbcFrozenInputs.canonical(body)));String key=OwnerJobCancelService.actionKey(requestKey);
        return writes.execute(new SharedWriteExecutor.ActionContext(key,root,null),k->store.committed(k,command),c->{
            var original=store.pending(command);String snapshot=inputs.read(root,original.root().hash());if(snapshot.getBytes(StandardCharsets.UTF_8).length>4*1024*1024)throw new IllegalStateException("original result input capacity unavailable");
            var context=FrozenApplicationContext.fromJson(CanonicalJson.parse(snapshot).get(FrozenApplicationContext.FIELD));if(context.generation()!=original.root().generation()||!context.stage().name().toLowerCase(Locale.ROOT).equals(original.type()))throw new IllegalStateException("original result input binding unavailable");
            var input=context.input(root,original.identity().attemptId(),original.root().hash(),revision);var result=ResultApplicationService.validate(input,original.envelope());if(!result.resultHash().equals(original.root().resultHash()))throw new IllegalStateException("original result hash unavailable");
            return store.discard(c.actionKey(),command,original,clock.instant().truncatedTo(ChronoUnit.MICROS));
        });
    }
}
