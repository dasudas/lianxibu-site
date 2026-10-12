package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CollectorHeartbeatTelemetry;
import com.lianxibu.site.repository.jobs.JdbcCollectorHeartbeats;
import com.lianxibu.site.service.identity.NodeIdentityService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Current node authorization is rechecked for every replay and under the original shared writer. */
public final class CollectorHeartbeatService {
    private final JdbcCollectorHeartbeats heartbeats;
    private final NodeIdentityService nodes;
    private final SharedWriteExecutor writes;
    private final Clock clock;
    public CollectorHeartbeatService(JdbcCollectorHeartbeats heartbeats,NodeIdentityService nodes,SharedWriteExecutor writes,Clock clock){this.heartbeats=Objects.requireNonNull(heartbeats);this.nodes=Objects.requireNonNull(nodes);this.writes=Objects.requireNonNull(writes);this.clock=Objects.requireNonNull(clock);}
    public JdbcCollectorHeartbeats.Entry submit(String bearer,byte[] raw){
        var input=CollectorHeartbeatTelemetry.parse(raw);var initial=nodes.authorizeBearer(bearer,"heartbeat:write",null);
        String action=sha(initial.nodeId()+"\n"+initial.revision()+"\n"+input.payloadHash());
        var context=new SharedWriteExecutor.ActionContext("collector-heartbeat:"+action,"collector-heartbeat",input.payloadHash());
        return writes.execute(context,key->{current(bearer,initial);return heartbeats.committed(initial.nodeId(),initial.revision(),input);},key->{
            current(bearer,initial);var now=clock.instant().truncatedTo(ChronoUnit.MICROS);
            if(input.sample().observedAt().isAfter(now)||Duration.between(input.sample().observedAt(),now).compareTo(Duration.ofSeconds(120))>0)throw new IllegalArgumentException("stale collector heartbeat");
            return heartbeats.save(initial.nodeId(),initial.revision(),input,now);
        });
    }
    private void current(String bearer,NodeIdentityService.Principal initial){var value=nodes.authorizeBearer(bearer,"heartbeat:write",null);if(!initial.nodeId().equals(value.nodeId())||initial.revision()!=value.revision())throw new IllegalStateException("collector identity changed");}
    private static String sha(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException("collector heartbeat unavailable");}}
}
