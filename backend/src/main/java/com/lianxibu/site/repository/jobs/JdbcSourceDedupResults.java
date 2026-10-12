package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;

/** Commit-visible original dedup result storage; never joins the result writer. */
public final class JdbcSourceDedupResults {
 public record Root(String id,String intent,String key,byte[] keyHash,long generation,long part,String type,
                    String inputHash,String state,String application,String resultHash,String manifest,Instant appliedAt,int calls,int maximum){
  public Root{keyHash=keyHash==null?null:keyHash.clone();}
  @Override public byte[] keyHash(){return keyHash==null?null:keyHash.clone();}
  @Override public String toString(){return "SourceDedupResultRoot[redacted]";}
 }
 private final JdbcTemplate jdbc;
 public JdbcSourceDedupResults(DataSource source){jdbc=new JdbcTemplate(Objects.requireNonNull(source));}
 public Root root(String id){
  requireReadOnly();WireValues.id(id,false);
  var rows=jdbc.query("SELECT id,intent_id,CASE WHEN OCTET_LENGTH(logical_key) BETWEEN 1 AND 4096 THEN logical_key ELSE NULL END,logical_key_hash,generation,stage_part,task_type,LOWER(HEX(input_hash)),state,application_state,LOWER(HEX(result_hash)),"
   +"CASE WHEN OCTET_LENGTH(result_json) BETWEEN 1 AND 4096 THEN result_json ELSE NULL END,applied_at,call_count,max_attempts,OCTET_LENGTH(result_json) FROM jobs WHERE id=? AND record_kind='logical_task' LIMIT 2",(r,n)->{
    Long generation=r.getObject(5,Long.class),part=r.getObject(6,Long.class);Integer calls=r.getObject(14,Integer.class),maximum=r.getObject(15,Integer.class);Long size=r.getObject(16,Long.class);
    if(generation==null||part==null||calls==null||maximum==null||size!=null&&(size<1||size>4096))throw invalid();var at=r.getTimestamp(13);
    return new Root(r.getString(1),r.getString(2),r.getString(3),r.getBytes(4),generation,part,r.getString(7),r.getString(8),r.getString(9),r.getString(10),r.getString(11),r.getString(12),at==null?null:at.toInstant(),calls,maximum);
   },id);
  if(rows.size()!=1||!id.equals(rows.getFirst().id()))throw invalid();return rows.getFirst();
 }
 public String handoff(String root){
  requireReadOnly();WireValues.id(root,false);String action="member-stage:"+root,id=JdbcFrozenInputs.stableId("member-stage",action);
  var rows=jdbc.query("SELECT id,record_kind,target_type,CASE WHEN OCTET_LENGTH(target_key_json) BETWEEN 1 AND 4096 THEN target_key_json ELSE NULL END,target_key_hash,operator_id,reason,"
   +"CASE WHEN OCTET_LENGTH(action_payload) BETWEEN 1 AND 524288 THEN action_payload ELSE NULL END,action_payload_hash,action_key_hash,OCTET_LENGTH(target_key_json),OCTET_LENGTH(action_payload) FROM jobs WHERE id=? OR action_key_hash=? LIMIT 3",(r,n)->{
    Long targetBytes=r.getObject(11,Long.class),payloadBytes=r.getObject(12,Long.class);if(targetBytes==null||targetBytes<1||targetBytes>4096||payloadBytes==null||payloadBytes<1||payloadBytes>524288)throw invalid();
    String target=r.getString(4),payload=r.getString(8);var key=CanonicalJson.parse(target);var expected=Set.of("kind","id");WireValues.object(key,expected,expected);
    if(!id.equals(r.getString(1))||!"disposition".equals(r.getString(2))||!"source_member_stage".equals(r.getString(3))||!"logical_task".equals(WireValues.text(key.get("kind")))||!root.equals(WireValues.text(key.get("id")))
      ||!"analysis-worker".equals(r.getString(6))||!"Source member fragment applied".equals(r.getString(7))||!Arrays.equals(r.getBytes(5),JdbcGenerationLineage.hash(JdbcFrozenInputs.canonical(key)))
      ||!Arrays.equals(r.getBytes(9),JdbcGenerationLineage.hash(JdbcFrozenInputs.canonical(CanonicalJson.parse(payload))))||!Arrays.equals(r.getBytes(10),JdbcGenerationLineage.hash(action)))throw invalid();
    String canonical=JdbcFrozenInputs.canonical(CanonicalJson.parse(payload));if(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>262144)throw invalid();return canonical;
   },id,JdbcGenerationLineage.hash(action));
  if(rows.size()>1)throw invalid();return rows.isEmpty()?null:rows.getFirst();
 }
 public String retained(Root root){
  requireReadOnly();if(root==null||root.resultHash()==null||!root.resultHash().matches("[0-9a-f]{64}")||root.manifest()==null)throw invalid();
  var manifest=CanonicalJson.parse(root.manifest());var fields=Set.of("schema_version","part_id","result_hash");WireValues.object(manifest,fields,fields);
  String id=JdbcFrozenInputs.stableId("retained-result",root.id()+":"+root.resultHash());
  if(!"retained-ai-result-manifest-v1".equals(WireValues.text(manifest.get("schema_version")))||!id.equals(WireValues.text(manifest.get("part_id")))||!root.resultHash().equals(WireValues.text(manifest.get("result_hash"))))throw invalid();
  var rows=jdbc.query("SELECT id,record_kind,owner_job_id,purpose,stamp_id,part_no,CASE WHEN OCTET_LENGTH(payload_json) BETWEEN 1 AND 524288 THEN payload_json ELSE NULL END,payload_hash,OCTET_LENGTH(payload_json) FROM jobs WHERE id=? LIMIT 2",(r,n)->{
   Long size=r.getObject(9,Long.class),number=r.getObject(6,Long.class);if(size==null||size<1||size>524288||number==null)throw invalid();String payload=r.getString(7);JsonNode body=CanonicalJson.parse(payload);
   if(!id.equals(r.getString(1))||!"snapshot_part".equals(r.getString(2))||!root.id().equals(r.getString(3))||!"result".equals(r.getString(4))||!root.resultHash().equals(r.getString(5))||number!=0
      ||!Arrays.equals(r.getBytes(8),HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,body))))throw invalid();return JdbcFrozenInputs.canonical(body);
  },id);
  if(rows.size()!=1)throw invalid();return rows.getFirst();
 }
 public static void requireReadOnly(){if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw invalid();}
 private static IllegalStateException invalid(){return new IllegalStateException("original source dedup result storage unavailable");}
}
