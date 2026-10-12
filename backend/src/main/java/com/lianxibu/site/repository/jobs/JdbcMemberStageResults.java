package com.lianxibu.site.repository.jobs;
import com.lianxibu.site.domain.protocol.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** One immutable result-fragment handoff per logical root. */
public final class JdbcMemberStageResults {
 public record Root(long generation,long part,String stage) { }
 private final JdbcTemplate jdbc;private final JdbcEntityDecisions receipts;private final JdbcJobRecords records;
 public JdbcMemberStageResults(DataSource source){jdbc=new JdbcTemplate(source);receipts=new JdbcEntityDecisions(source);records=new JdbcJobRecords(source);}
 public Root root(String id){var rows=jdbc.query("SELECT generation,stage_part,task_type FROM jobs WHERE id=? AND record_kind='logical_task'",(r,n)->new Root(r.getLong(1),r.getLong(2),r.getString(3)),id);if(rows.size()!=1)throw invalid();return rows.getFirst();}
 public JsonNode read(String root){String action=action(root);var row=receipts.read(JdbcFrozenInputs.stableId("member-stage",action),action);if(row==null)return null;
  if(!"source_member_stage".equals(row.targetType())||!"analysis-worker".equals(row.operatorId())||!target(root).equals(JdbcFrozenInputs.canonical(CanonicalJson.parse(row.targetKeyJson())))||!"Source member fragment applied".equals(row.reason()))throw invalid();return CanonicalJson.parse(row.payloadJson());}
 public void save(String root,JsonNode payload,Instant now){JdbcFrozenInputs.requireTx();String encoded=JdbcFrozenInputs.canonical(payload);if(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>262144)throw invalid();String action=action(root);records.appendDisposition(new JdbcJobRecords.Disposition(JdbcFrozenInputs.stableId("member-stage",action),action,"source_member_stage",target(root),"analysis-worker","Source member fragment applied",encoded),now);}
 private static String action(String root){WireValues.id(root,false);return "member-stage:"+root;}
 private static String target(String root){return JdbcFrozenInputs.canonical(JsonMapper.shared().createObjectNode().put("kind","logical_task").put("id",root));}
 private static IllegalStateException invalid(){return new IllegalStateException("member stage result proof unavailable");}
}
