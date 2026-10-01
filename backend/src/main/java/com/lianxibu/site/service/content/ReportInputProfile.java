package com.lianxibu.site.service.content;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Validates the COMPLETE immutable public metadata manifest before any page is selected.
 * Raw bodies and current database rows cannot supply missing profile facts. */
public final class ReportInputProfile {
 public static final String FIELD="report_summary_v1";
 public static final List<String> DAILY_GROUPS=List.of("source_summaries","high_value_items","historical_heat","new_catalog_analysis");
 private static final Set<String> PROFILE=Set.of("schema_version","report_type","period_start","period_end","timezone","selection_version","coverage","group_counts","total","members");
 private static final Set<String> MEMBER=Set.of("member_id","group","resource_ref","revision","revision_id","material_hash","source_id","source_run_id","source_item_id","business_date","title","summary","occurrence_count","rank","included","exclusion_reason");
 private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
 private static final tools.jackson.databind.json.JsonMapper JSON=tools.jackson.databind.json.JsonMapper.builder().build();
 private static final DateTimeFormatter MICROS=new DateTimeFormatterBuilder().appendInstant(6).toFormatter();
 private ReportInputProfile() { }
 public static void validateRoot(JsonNode root) {
  if(root==null||!root.isObject()||!"1".equals(WireValues.text(root.get("schema_version"))))throw invalid();
  WireValues.nonnegativeInteger(root.get("generation"));if(!Set.of("daily_report","weekly_report","monthly_report").contains(WireValues.text(root.get("stage")))||!Set.of("complete","partial","no_input").contains(WireValues.text(root.get("coverage_state"))))throw invalid();
  WireValues.businessDate(root.get("business_date"));WireValues.sha256(root.get("settings_hash"));boundedText(root.get("logical_key"),1,256,false);
  Instant cutoff=Instant.parse(WireValues.text(root.get("cutoff")));if(cutoff.getNano()%1000!=0)throw invalid();WireValues.utcMicroseconds(textNode(MICROS.format(cutoff)));
  if(root.get("missing")==null||!root.get("missing").isArray()||root.get("members")==null||!root.get("members").isArray())throw invalid();
 }
 public static Map<String,Object> validated(JsonNode root) {
  validateRoot(root);
  if(root==null||!root.isObject()||!"1".equals(WireValues.text(root.get("schema_version"))))throw invalid();
  long generation=WireValues.nonnegativeInteger(root.get("generation"));if(generation<1)throw invalid();
  Instant cutoff=Instant.parse(WireValues.text(root.get("cutoff")));if(cutoff.getNano()%1000!=0)throw invalid();
  String cutoffWire=MICROS.format(cutoff);WireValues.utcMicroseconds(textNode(cutoffWire));
  JsonNode p=closed(root.get(FIELD),PROFILE);if(!"report-input-summary-v1".equals(WireValues.text(p.get("schema_version"))))throw invalid();
  String type=WireValues.text(p.get("report_type"));if(!Set.of("daily","weekly","monthly").contains(type)||!(type+"_report").equals(WireValues.text(root.get("stage")))||!"Asia/Shanghai".equals(WireValues.text(p.get("timezone"))))throw invalid();
  Instant start=WireValues.utcMicroseconds(p.get("period_start")),end=WireValues.utcMicroseconds(p.get("period_end"));LocalDate first=start.atZone(ZONE).toLocalDate(),last=end.atZone(ZONE).toLocalDate();
  if(!first.atStartOfDay(ZONE).toInstant().equals(start)||!last.atStartOfDay(ZONE).toInstant().equals(end)||!last.isAfter(first))throw invalid();
  boolean canonical=switch(type){case "daily"->last.equals(first.plusDays(1));case "weekly"->first.getDayOfWeek()==DayOfWeek.MONDAY&&last.equals(first.plusDays(7));default->first.getDayOfMonth()==1&&last.equals(first.plusMonths(1));};
  if(!canonical||cutoff.isBefore(end)||!WireValues.businessDate(root.get("business_date")).equals(last.minusDays(1)))throw invalid();
  nullableText(p.get("selection_version"),1,128,false);coverage(p.get("coverage"),first,last);if(!WireValues.text(p.get("coverage").get("state")).equals(WireValues.text(root.get("coverage_state"))))throw invalid();
  List<String> groups=type.equals("daily")?DAILY_GROUPS:List.of("upstream_reports");JsonNode members=p.get("members");if(members==null||!members.isArray()||WireValues.nonnegativeInteger(p.get("total"))!=members.size())throw invalid();
  var seenIds=new HashSet<String>();var seenIdentities=new HashSet<String>();var counts=new LinkedHashMap<String,long[]>();for(String g:groups)counts.put(g,new long[2]);var heat=new ArrayList<JsonNode>();var sorted=new ArrayList<JsonNode>();
  for(JsonNode member:members){closed(member,MEMBER);String memberId=WireValues.id(member.get("member_id"),false),group=WireValues.text(member.get("group"));if(!counts.containsKey(group)||!seenIds.add(memberId))throw invalid();
   JsonNode ref=closed(member.get("resource_ref"),Set.of("kind","id","package_id"));boundedText(ref.get("kind"),1,32,false);WireValues.id(ref.get("id"),false);nullableId(ref.get("package_id"),false);
   if(group.equals("upstream_reports")&&!"report".equals(WireValues.text(ref.get("kind"))))throw invalid();
   nullableInteger(member.get("revision"));nullableId(member.get("revision_id"),false);if(!member.get("material_hash").isNull())WireValues.sha256(member.get("material_hash"));nullableId(member.get("source_id"),true);nullableId(member.get("source_run_id"),false);nullableText(member.get("source_item_id"),1,4096,true);
   if(!member.get("business_date").isNull()){LocalDate day=WireValues.businessDate(member.get("business_date"));if(group.equals("upstream_reports")&&(day.isBefore(first)||!day.isBefore(last)))throw invalid();}
   nullableText(member.get("title"),1,512,false);nullableText(member.get("summary"),0,16384,false);nullableInteger(member.get("occurrence_count"));if(!member.get("included").isBoolean())throw invalid();boolean included=member.get("included").booleanValue();
   if(included){if(!member.get("exclusion_reason").isNull())throw invalid();}else{boundedText(member.get("exclusion_reason"),1,256,false);if(WireValues.text(member.get("exclusion_reason")).isBlank())throw invalid();}
   if(group.equals("historical_heat")&&included){WireValues.nonnegativeInteger(member.get("occurrence_count"));long rank=WireValues.nonnegativeInteger(member.get("rank"));if(rank<1||rank>5)throw invalid();heat.add(member);}else if(!member.get("rank").isNull())throw invalid();
   // Complete input identity includes the group, exact version and source occurrence; display fields do not identify a member.
   var identity=JSON.createObjectNode();for(String field:List.of("group","resource_ref","revision","revision_id","material_hash","source_id","source_run_id","source_item_id","business_date"))identity.set(field,member.get(field));
   if(!seenIdentities.add(new String(CanonicalJson.bytes(identity),StandardCharsets.UTF_8)))throw invalid();counts.get(group)[included?0:1]++;sorted.add(member);
  }
  JsonNode groupCounts=p.get("group_counts");if(groupCounts==null||!groupCounts.isArray()||groupCounts.size()!=groups.size())throw invalid();for(int i=0;i<groups.size();i++){JsonNode item=closed(groupCounts.get(i),Set.of("group","included_count","excluded_count"));String group=groups.get(i);if(!group.equals(WireValues.text(item.get("group")))||WireValues.nonnegativeInteger(item.get("included_count"))!=counts.get(group)[0]||WireValues.nonnegativeInteger(item.get("excluded_count"))!=counts.get(group)[1])throw invalid();}
  if(heat.size()>5)throw invalid();heat.sort((a,b)->{int count=Long.compare(WireValues.nonnegativeInteger(b.get("occurrence_count")),WireValues.nonnegativeInteger(a.get("occurrence_count")));if(count!=0)return count;return utf8(WireValues.text(a.get("resource_ref").get("id")),WireValues.text(b.get("resource_ref").get("id")));});for(int i=0;i<heat.size();i++)if(WireValues.nonnegativeInteger(heat.get(i).get("rank"))!=i+1)throw invalid();
  sorted.sort((a,b)->{int group=Integer.compare(groups.indexOf(WireValues.text(a.get("group"))),groups.indexOf(WireValues.text(b.get("group"))));return group!=0?group:utf8(WireValues.text(a.get("member_id")),WireValues.text(b.get("member_id")));});
  var result=new LinkedHashMap<String,Object>();result.put("generation",generation);result.put("input_cutoff_at",cutoffWire);for(String field:List.of("report_type","period_start","period_end","timezone","selection_version","coverage","group_counts","total"))result.put(field,immutable(p.get(field)));result.put("items",sorted.stream().map(ReportInputProfile::immutable).toList());return Collections.unmodifiableMap(result);
 }
 private static void coverage(JsonNode value,LocalDate first,LocalDate last){closed(value,Set.of("state","missing_inputs","missing_dates","no_update_dates"));String state=WireValues.text(value.get("state"));if(!Set.of("complete","partial","no_input").contains(state))throw invalid();JsonNode inputs=value.get("missing_inputs");if(!inputs.isArray()||inputs.size()>1000)throw invalid();var identities=new HashSet<String>();for(JsonNode item:inputs){closed(item,Set.of("kind","id","business_date","reason"));boundedText(item.get("kind"),1,32,false);nullableId(item.get("id"),false);if(!item.get("business_date").isNull())WireValues.businessDate(item.get("business_date"));boundedText(item.get("reason"),1,256,false);if(!identities.add(new String(CanonicalJson.bytes(item),StandardCharsets.UTF_8)))throw invalid();}Set<LocalDate> missing=dates(value.get("missing_dates"),first,last),noUpdate=dates(value.get("no_update_dates"),first,last);if(!Collections.disjoint(missing,noUpdate)||state.equals("complete")&&(!inputs.isEmpty()||!missing.isEmpty()))throw invalid();}
 private static Set<LocalDate> dates(JsonNode value,LocalDate first,LocalDate last){if(value==null||!value.isArray()||value.size()>366)throw invalid();Set<LocalDate> seen=new HashSet<>();for(JsonNode item:value){LocalDate date=WireValues.businessDate(item);if(date.isBefore(first)||!date.isBefore(last)||!seen.add(date))throw invalid();}return seen;}
 private static JsonNode closed(JsonNode value,Set<String> fields){return WireValues.object(value,fields,fields);}
 private static void nullableInteger(JsonNode value){if(!value.isNull())WireValues.nonnegativeInteger(value);}
 private static void nullableId(JsonNode value,boolean source){if(!value.isNull())WireValues.id(value,source);}
 private static void nullableText(JsonNode value,int min,int max,boolean identity){if(!value.isNull())boundedText(value,min,max,identity);}
 private static void boundedText(JsonNode value,int min,int max,boolean identity){String s=WireValues.text(value);int length=s.codePointCount(0,s.length());if(length<min||length>max||identity&&s.codePoints().anyMatch(Character::isISOControl))throw invalid();}
 private static JsonNode textNode(String value){return tools.jackson.databind.node.StringNode.valueOf(value);}
 private static int utf8(String a,String b){return Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));}
 private static Object immutable(JsonNode value){if(value.isObject()){Map<String,Object> result=new LinkedHashMap<>();value.properties().forEach(e->result.put(e.getKey(),immutable(e.getValue())));return Collections.unmodifiableMap(result);}if(value.isArray()){List<Object> result=new ArrayList<>();value.forEach(v->result.add(immutable(v)));return List.copyOf(result);}if(value.isNull())return null;if(value.isTextual())return value.textValue();if(value.isBoolean())return value.booleanValue();return WireValues.nonnegativeInteger(value);}
 private static IllegalArgumentException invalid(){return new IllegalArgumentException("invalid frozen report profile");}
}
