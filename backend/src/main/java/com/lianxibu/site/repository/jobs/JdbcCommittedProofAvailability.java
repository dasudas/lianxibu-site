package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Internal bounded ledger shared by closed source proof observation facades. */
final class JdbcCommittedProofAvailability {
 private final String TYPE,ACTOR,REASON,prefix,identityKind,version;
 private final JdbcTemplate jdbc;private final JdbcJobRecords records;
 JdbcCommittedProofAvailability(DataSource source,String type,String actor,String reason,String prefix,String identityKind,String version){jdbc=new JdbcTemplate(Objects.requireNonNull(source));records=new JdbcJobRecords(source);TYPE=Objects.requireNonNull(type);ACTOR=Objects.requireNonNull(actor);REASON=Objects.requireNonNull(reason);this.prefix=Objects.requireNonNull(prefix);this.identityKind=Objects.requireNonNull(identityKind);this.version=Objects.requireNonNull(version);}
 public Instant read(String target){
  requireTransaction();validateTarget(target);String action=key(target),id=JdbcFrozenInputs.stableId(identityKind,action);
  var rows=jdbc.query("SELECT id,record_kind,target_type,CASE WHEN OCTET_LENGTH(target_key_json) BETWEEN 1 AND 2048 THEN target_key_json ELSE NULL END,target_key_hash,operator_id,reason,"
    +"CASE WHEN OCTET_LENGTH(action_payload) BETWEEN 1 AND 2048 THEN action_payload ELSE NULL END,action_payload_hash,action_key_hash,OCTET_LENGTH(target_key_json),OCTET_LENGTH(action_payload),acted_at FROM jobs WHERE id=? OR action_key_hash=? LIMIT 3",(r,n)->{
    Long targetBytes=r.getObject(11,Long.class),payloadBytes=r.getObject(12,Long.class);if(targetBytes==null||targetBytes<1||targetBytes>2048||payloadBytes==null||payloadBytes<1||payloadBytes>2048)throw invalid();
    var savedTarget=CanonicalJson.parse(r.getString(4));var payload=CanonicalJson.parse(r.getString(8));var fields=Set.of("schema_version","proof_hash","observed_at");WireValues.object(payload,fields,fields);
    if(!id.equals(r.getString(1))||!"disposition".equals(r.getString(2))||!TYPE.equals(r.getString(3))||!target.equals(JdbcFrozenInputs.canonical(savedTarget))||!ACTOR.equals(r.getString(6))||!REASON.equals(r.getString(7))
      ||!Arrays.equals(r.getBytes(5),JdbcGenerationLineage.hash(target))||!Arrays.equals(r.getBytes(9),JdbcGenerationLineage.hash(JdbcFrozenInputs.canonical(payload)))||!Arrays.equals(r.getBytes(10),JdbcGenerationLineage.hash(action))
      ||!version.equals(WireValues.text(payload.get("schema_version")))||!CanonicalJson.hash(CanonicalJson.Domain.ACTION,savedTarget).equals(WireValues.sha256(payload.get("proof_hash"))))throw invalid();
    Instant observed=WireValues.utcMicroseconds(payload.get("observed_at"));var acted=r.getTimestamp(13,Calendar.getInstance(TimeZone.getTimeZone("UTC")));if(acted==null||!observed.equals(acted.toInstant()))throw invalid();return observed;
   },id,JdbcGenerationLineage.hash(action));
  if(rows.size()>1)throw invalid();return rows.isEmpty()?null:rows.getFirst();
 }
 public void save(String target,Instant observed){
  JdbcFrozenInputs.requireTx();validateTarget(target);if(observed==null||observed.getNano()%1000!=0)throw invalid();var old=read(target);if(old!=null){if(!old.equals(observed))throw invalid();return;}
  var payload=JsonMapper.shared().createObjectNode().put("schema_version",version).put("proof_hash",CanonicalJson.hash(CanonicalJson.Domain.ACTION,CanonicalJson.parse(target)))
    .put("observed_at",new DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(observed));String action=key(target);
  records.appendDispositionUtc(new JdbcJobRecords.Disposition(JdbcFrozenInputs.stableId(identityKind,action),action,TYPE,target,ACTOR,REASON,JdbcFrozenInputs.canonical(payload)),observed);
 }
 String key(String target){validateTarget(target);return prefix+JdbcResultState.sha(target);}
 private static void validateTarget(String target){if(target==null||target.getBytes(StandardCharsets.UTF_8).length>2048)throw invalid();var n=CanonicalJson.parse(target);if(!n.isObject()||!target.equals(JdbcFrozenInputs.canonical(n)))throw invalid();}
 private static void requireTransaction(){if(!TransactionSynchronizationManager.isActualTransactionActive())throw invalid();}
 private static IllegalStateException invalid(){return new IllegalStateException("committed proof observation unavailable");}
}
