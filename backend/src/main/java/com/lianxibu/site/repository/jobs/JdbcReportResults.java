package com.lianxibu.site.repository.jobs;
import com.lianxibu.site.domain.protocol.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Report completion and initial period intentions share the body transaction. */
public final class JdbcReportResults {
 private final JdbcEntityDecisions receipts;private final JdbcJobRecords records;
 public JdbcReportResults(DataSource source){receipts=new JdbcEntityDecisions(source);records=new JdbcJobRecords(source);}
 public JsonNode read(String root){String key=key(root);var row=receipts.read(JdbcFrozenInputs.stableId("report-ready",key),key);if(row==null)return null;if(!"report_result_ready".equals(row.targetType())||!"analysis-worker".equals(row.operatorId())||!target(root).equals(JdbcFrozenInputs.canonical(CanonicalJson.parse(row.targetKeyJson())))||!"Report version completed".equals(row.reason()))throw invalid();return CanonicalJson.parse(row.payloadJson());}
 public String ensurePeriod(String type,LocalDate date,Instant now){JdbcFrozenInputs.requireTx();if(!Set.of("weekly","monthly").contains(type)||date==null)throw invalid();String logical="report:"+type+":"+date,id=JdbcFrozenInputs.stableId("intent",logical+":1");if(!id.equals(records.ensureIntent(new JdbcJobRecords.Intent(id,logical,1,type+"_report",date),now)))throw invalid();return id;}
 public void save(String root,JsonNode payload,Instant now){JdbcFrozenInputs.requireTx();String key=key(root);records.appendDispositionUtc(new JdbcJobRecords.Disposition(JdbcFrozenInputs.stableId("report-ready",key),key,"report_result_ready",target(root),"analysis-worker","Report version completed",JdbcFrozenInputs.canonical(payload)),now);}
 private static String key(String root){WireValues.id(root,false);return "report-ready:"+root;}
 private static String target(String root){return JdbcFrozenInputs.canonical(JsonMapper.shared().createObjectNode().put("kind","logical_task").put("id",root));}
 private static IllegalStateException invalid(){return new IllegalStateException("report result handoff unavailable");}
}
