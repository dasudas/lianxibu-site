package com.lianxibu.site.service.content;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Entirely fictional, isolated frozen inputs; never connects to a database or provider. */
public final class ReportInputFixtures {
 public static final JsonMapper JSON=JsonMapper.builder().build();
 public static final List<String> GROUPS=List.of("source_summaries","high_value_items","historical_heat","new_catalog_analysis");
 private ReportInputFixtures() { }
 public static ObjectNode root(String type,int count) {
  var root=JSON.createObjectNode();root.put("schema_version","1");root.put("logical_key","fictional-report-input");root.put("generation",2);root.put("stage",type+"_report");root.put("business_date",type.equals("weekly")?"2026-01-11":type.equals("monthly")?"2026-01-31":"2026-01-05");root.put("cutoff","2026-02-02T01:00:00Z");root.put("coverage_state","complete");root.put("settings_hash","a".repeat(64));root.putArray("missing");root.putArray("members").addObject().put("body","Fictional internal body must never escape");
  var profile=root.putObject("report_summary_v1");profile.put("schema_version","report-input-summary-v1");profile.put("report_type",type);profile.put("period_start",type.equals("monthly")?"2025-12-31T16:00:00.000000Z":"2026-01-04T16:00:00.000000Z");profile.put("period_end",type.equals("weekly")?"2026-01-11T16:00:00.000000Z":type.equals("monthly")?"2026-01-31T16:00:00.000000Z":"2026-01-05T16:00:00.000000Z");profile.put("timezone","Asia/Shanghai");profile.putNull("selection_version");var coverage=profile.putObject("coverage");coverage.put("state","complete");coverage.putArray("missing_inputs");coverage.putArray("missing_dates");coverage.putArray("no_update_dates");var groups=profile.putArray("group_counts");for(String group:type.equals("daily")?GROUPS:List.of("upstream_reports")){var item=groups.addObject();item.put("group",group);item.put("included_count",group.equals("source_summaries")||group.equals("upstream_reports")?count:0);item.put("excluded_count",0);}
  profile.put("total",count);var members=profile.putArray("members");for(int i=count-1;i>=0;i--)members.add(member(i,type.equals("daily")?"source_summaries":"upstream_reports",true));return root;
 }
 public static ObjectNode member(int i,String group,boolean included) {
  var item=JSON.createObjectNode();item.put("member_id","fictional-member-"+String.format("%03d",i));item.put("group",group);var ref=item.putObject("resource_ref");ref.put("kind",group.equals("upstream_reports")?"report":"article");ref.put("id","fictional-resource-"+i);ref.putNull("package_id");for(String field:List.of("revision","revision_id","material_hash","source_id","source_run_id","source_item_id","business_date","title","summary","occurrence_count","rank"))item.putNull(field);item.put("included",included);if(included)item.putNull("exclusion_reason");else item.put("exclusion_reason","Fictional excluded at cutoff");return item;
 }
 public static ReportInputSummaryReadService.Proof proof(String report,String snapshot,ObjectNode root) {
  String raw=new String(CanonicalJson.bytes(root),StandardCharsets.UTF_8);return new ReportInputSummaryReadService.Proof(report,snapshot,new ReportInputSummaryReadService.Snapshot(snapshot,CanonicalJson.hash(CanonicalJson.Domain.INPUT,root),raw),null);
 }
 public static byte[] key(){return new byte[32];}
}
