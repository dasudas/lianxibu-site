package com.lianxibu.site.service.sources;
import com.lianxibu.site.domain.protocol.SourceContractTelemetry;import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.repository.sources.JdbcSourceContractSnapshots;import com.lianxibu.site.repository.sources.JdbcSourceAdmission;
import com.lianxibu.site.service.identity.NodeIdentityService;import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import java.time.*;import java.time.temporal.ChronoUnit;import java.util.Objects;
/** Authenticated health facts only; submission never claims a run or grants source production admission. */
public final class SourceContractSnapshotService {
 public static final class Rejected extends IllegalArgumentException {private final String code;public Rejected(String code){super(code);this.code=code;}public String code(){return code;}}
 private final JdbcSourceContractSnapshots snapshots;private final JdbcSourceAdmission sources;private final NodeIdentityService nodes;private final SharedWriteExecutor writes;private final Clock clock;
 public SourceContractSnapshotService(JdbcSourceContractSnapshots snapshots,JdbcSourceAdmission sources,NodeIdentityService nodes,SharedWriteExecutor writes,Clock clock){this.snapshots=Objects.requireNonNull(snapshots);this.sources=Objects.requireNonNull(sources);this.nodes=Objects.requireNonNull(nodes);this.writes=Objects.requireNonNull(writes);this.clock=Objects.requireNonNull(clock);}
 public JdbcSourceContractSnapshots.Receipt submit(String bearer,String sourceId,byte[] raw){
  WireValues.id(sourceId,true);var input=SourceContractTelemetry.parse(raw);if(!sourceId.equals(input.snapshot().sourceId()))throw new Rejected("validation");
  var initial=nodes.authorizeBearer(bearer,"heartbeat:write",sourceId);String action=JdbcSourceContractSnapshots.actionKey(initial.nodeId(),input.snapshot());var context=new SharedWriteExecutor.ActionContext("source-telemetry:"+action,"source-telemetry",input.payloadHash());
  return writes.execute(context,key->{var current=nodes.authorizeBearer(bearer,"heartbeat:write",sourceId);if(!initial.nodeId().equals(current.nodeId()))throw new Rejected("forbidden");return snapshots.committed(current.nodeId(),input);},key->{
   var current=nodes.authorizeBearer(bearer,"heartbeat:write",sourceId);if(!initial.nodeId().equals(current.nodeId()))throw new Rejected("forbidden");var source=sources.locked(sourceId);Instant now=clock.instant().truncatedTo(ChronoUnit.MICROS);
   if(source==null||!"production".equals(source.activationStage())||source.configRevision()!=input.snapshot().configVersion()||source.effectiveConfigJson()==null||source.configValidUntil()==null||!source.configValidUntil().isAfter(now))throw new Rejected("stale_config");
   return snapshots.save(current.nodeId(),current.revision(),input,now);
  });
 }
}
