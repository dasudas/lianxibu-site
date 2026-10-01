package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.service.content.ReportInputProfile;
import com.lianxibu.site.service.jobs.GenerationFlowService.Evidence;
import com.lianxibu.site.service.jobs.GenerationFlowService.Prepare;
import com.lianxibu.site.service.jobs.GenerationFlowService.Stage;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Pure report selection and immutable snapshot construction. Source is database-only;
 * callers own the shared transaction. No network call or writer is hidden here. */
public final class ReportInputSnapshotService {
 public enum Group {
  SOURCE_SUMMARIES("source_summaries"), HIGH_VALUE_ITEMS("high_value_items"),
  HISTORICAL_HEAT("historical_heat"), NEW_CATALOG_ANALYSIS("new_catalog_analysis"),
  UPSTREAM_REPORTS("upstream_reports");
  private final String wire;
  Group(String wire){this.wire=wire;}
  public String wire(){return wire;}
 }
 public record ResourceRef(String kind,String id,String packageId) { }
 /** Public facts supplied explicitly from immutable versions; null stays unknown. */
 public record Metadata(ResourceRef resourceRef,Long revision,String revisionId,String materialHash,
                        String sourceId,String sourceRunId,String sourceItemId,LocalDate businessDate,
                        String title,String summary,Long occurrenceCount) { }
 public record Candidate(Group group,Metadata metadata,Evidence evidence,String category,
                         boolean highValue,boolean newOrReanalysisQualified,
                         Instant countCutoff,String exclusionReason) { }
 public record MissingInput(String kind,String id,LocalDate businessDate,String reason) { }
 /** complete asserts the ENTIRE candidate universe, dependency facts and historical counts
 * at this exact cutoff. Current counts or a later query filtered only by timestamp do not
 * prove this. Public summaries and selected bodies must come from already filtered,
 * effective immutable versions. noUpdateDates require persisted successful no-update evidence. */
 public record SourceProof(Instant cutoff,LocalDate periodStart,LocalDate periodEnd,boolean complete,
                           List<Candidate> candidates,List<MissingInput> missingInputs,
                           List<LocalDate> noUpdateDates,List<String> verifiedDependencyIds) {
  public SourceProof {
   candidates=List.copyOf(candidates);missingInputs=List.copyOf(missingInputs);
   noUpdateDates=List.copyOf(noUpdateDates);verifiedDependencyIds=List.copyOf(verifiedDependencyIds);
  }
 }
 @FunctionalInterface public interface Source {
  /** One consistent immutable read. Null/incomplete proof means unavailable, never empty. */
  SourceProof snapshot(Prepare command,Instant cutoff);
 }
 public record FrozenInput(String snapshotJson,String inputHash,String coverageState,
                           List<String> missing,boolean hasContent,String state) {
  public FrozenInput {missing=List.copyOf(missing);}
 }
 public static final class ProofUnavailable extends RuntimeException {
  public ProofUnavailable(){super("complete frozen report inputs unavailable");}
 }
 private static final JsonMapper JSON=JsonMapper.builder().build();
 private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
 private static final DateTimeFormatter MICROS=new DateTimeFormatterBuilder().appendInstant(6).toFormatter();
 private static final Set<String> ORDINARY_EXCLUSIONS=Set.of("not_high_value","not_new_or_reanalysis","not_in_top_five","no_daily_input","blocked");
 private final Source source;
 public ReportInputSnapshotService(Source source){this.source=Objects.requireNonNull(source,"report source required");}

 public FrozenInput freeze(Prepare command,Instant cutoff) {
  LocalDate first=validateCommand(command,cutoff),last=command.businessDate().plusDays(1);
  SourceProof proof=source.snapshot(command,cutoff);
  if(proof==null||!proof.complete()||!cutoff.equals(proof.cutoff())
      ||!first.equals(proof.periodStart())||!last.equals(proof.periodEnd()))throw new ProofUnavailable();
  var rows=new ArrayList<Row>();var memberIds=new HashSet<String>();var evidenceById=new HashMap<String,Evidence>();
  var verified=new HashSet<String>();
  for(String id:proof.verifiedDependencyIds()){required(id);if(!verified.add(id))throw invalid();}
  var heatEntities=new HashSet<String>();
  for(Candidate candidate:proof.candidates()){
   Row row=row(candidate,command,cutoff,first,last);
   if(!memberIds.add(row.id))throw invalid();
   if(candidate.group()==Group.HISTORICAL_HEAT&&!heatEntities.add(candidate.metadata().resourceRef().id()))throw new ProofUnavailable();
   if(candidate.evidence()!=null&&verified.contains(candidate.evidence().id()))throw invalid();
   rows.add(row);
   if(candidate.evidence()!=null){Evidence prior=evidenceById.putIfAbsent(candidate.evidence().id(),candidate.evidence());if(prior!=null&&!prior.equals(candidate.evidence()))throw invalid();}
  }
  rows.sort((a,b)->{int group=Integer.compare(a.c.group().ordinal(),b.c.group().ordinal());return group!=0?group:utf8(a.id,b.id);});
  // Dependency closure precedes ranking. A selected child cannot retain a body reference
  // to an excluded candidate. Ranking is recomputed if that closure removes a heat row.
  boolean changed;
  do {
   rank(rows);
   var available=new HashSet<>(verified);
   rows.stream().filter(Row::included).forEach(r->available.add(r.c.evidence().id()));
   changed=false;
   for(Row r:rows)if(r.reason==null&&!available.containsAll(r.c.evidence().references())){r.reason="missing_dependencies";changed=true;}
  } while(changed);
  rank(rows);
  if(command.stage()==Stage.DAILY_REPORT){
   boolean daily=rows.stream().anyMatch(r->r.included()&&r.c.group()!=Group.HISTORICAL_HEAT&& !"no_updates".equals(r.c.evidence().kind()));
   if(!daily)for(Row r:rows)if(r.included()&&r.c.group()==Group.HISTORICAL_HEAT){r.reason="no_daily_input";r.rank=null;}
  }
  var covered=new HashSet<LocalDate>();
  for(Row r:rows)if(r.included()){
   LocalDate date=r.c.evidence().businessDate();
   if(command.stage()!=Stage.DAILY_REPORT&&!covered.add(date))throw new ProofUnavailable();
   if(command.stage()==Stage.DAILY_REPORT&&r.c.group()!=Group.HISTORICAL_HEAT)covered.add(date);
  }
  var noUpdates=new TreeSet<LocalDate>();
  for(LocalDate day:proof.noUpdateDates())if(day==null||day.isBefore(first)||!day.isBefore(last)||!noUpdates.add(day)||covered.contains(day))throw invalid();
  var omissions=new TreeMap<String,MissingInput>(ReportInputSnapshotService::utf8);
  for(MissingInput missing:proof.missingInputs())addMissing(omissions,missing);
  for(Row r:rows)if(!r.included()&&!ORDINARY_EXCLUSIONS.contains(r.exclusion()))addMissing(omissions,new MissingInput("candidate",r.id,r.c.metadata().businessDate(),r.exclusion()));
  var dependencies=new HashSet<>(verified);
  for(Row r:rows)if(r.included())dependencies.add(r.c.evidence().id());
  if(command.expectedDependencies()==null)throw invalid();
  for(String id:command.expectedDependencies()){required(id);if(!dependencies.contains(id))addMissing(omissions,new MissingInput("dependency",id,null,"missing_at_cutoff"));}
  var missingDates=new ArrayList<LocalDate>();
  if(command.stage()!=Stage.DAILY_REPORT)for(LocalDate day=first;day.isBefore(last);day=day.plusDays(1))if(!covered.contains(day)&&!noUpdates.contains(day))missingDates.add(day);
  boolean hasContent=rows.stream().anyMatch(r->r.included()&&!"no_updates".equals(r.c.evidence().kind()));
  String coverage=!hasContent&&noUpdates.isEmpty()?"no_input":omissions.isEmpty()&&missingDates.isEmpty()?"complete":"partial";
  String state=hasContent?"queued":noUpdates.isEmpty()?"no_input":"coverage_only";
  ObjectNode root=JSON.createObjectNode();root.put("schema_version","1");root.put("logical_key",command.logicalKey());root.put("generation",command.generation());root.put("stage",command.stage().name().toLowerCase(Locale.ROOT));root.put("business_date",command.businessDate().toString());root.put("cutoff",MICROS.format(cutoff));root.put("settings_hash",command.settingsHash());root.put("coverage_state",coverage);
  var missingIds=new TreeSet<String>(ReportInputSnapshotService::utf8);
  omissions.values().forEach(m->missingIds.add(m.id()!=null?m.id():"missing:"+canonical(missingNode(m))));missingDates.forEach(d->missingIds.add("date:"+d));
  var rootMissing=root.putArray("missing");missingIds.forEach(rootMissing::add);
  var selected=root.putArray("members");var bodies=new TreeMap<String,Evidence>(ReportInputSnapshotService::utf8);
  for(Row r:rows)if(r.included())bodies.put(r.c.evidence().id(),r.c.evidence());
  for(Evidence e:bodies.values()){
   ObjectNode m=selected.addObject();m.put("id",e.id());m.put("kind",e.kind());m.put("business_date",e.businessDate().toString());m.put("completed_at",MICROS.format(e.completedAt()));m.put("body_hash",e.bodyHash());m.set("body",CanonicalJson.parse(e.bodyJson()));var refs=m.putArray("references");e.references().stream().distinct().sorted(ReportInputSnapshotService::utf8).forEach(refs::add);
  }
  ObjectNode profile=root.putObject(ReportInputProfile.FIELD);profile.put("schema_version","report-input-summary-v1");profile.put("report_type",command.stage().name().toLowerCase(Locale.ROOT).replace("_report",""));profile.put("period_start",MICROS.format(first.atStartOfDay(ZONE).toInstant()));profile.put("period_end",MICROS.format(last.atStartOfDay(ZONE).toInstant()));profile.put("timezone","Asia/Shanghai");profile.put("selection_version","report-input-selection-v1");
  ObjectNode cv=profile.putObject("coverage");cv.put("state",coverage);var missingInputs=cv.putArray("missing_inputs");omissions.values().forEach(m->missingInputs.add(missingNode(m)));var dates=cv.putArray("missing_dates");missingDates.forEach(d->dates.add(d.toString()));var emptyDates=cv.putArray("no_update_dates");noUpdates.forEach(d->emptyDates.add(d.toString()));
  var groupCounts=profile.putArray("group_counts");List<Group> groups=command.stage()==Stage.DAILY_REPORT?List.of(Group.SOURCE_SUMMARIES,Group.HIGH_VALUE_ITEMS,Group.HISTORICAL_HEAT,Group.NEW_CATALOG_ANALYSIS):List.of(Group.UPSTREAM_REPORTS);
  for(Group group:groups){ObjectNode counts=groupCounts.addObject();counts.put("group",group.wire());counts.put("included_count",rows.stream().filter(r->r.c.group()==group&&r.included()).count());counts.put("excluded_count",rows.stream().filter(r->r.c.group()==group&&!r.included()).count());}
  profile.put("total",rows.size());var publicMembers=profile.putArray("members");var decisions=root.putArray("report_candidate_decisions_v1");
  for(Row row:rows){ObjectNode m=row.metadata.deepCopy();m.put("member_id",row.id);m.put("included",row.included());put(m,"rank",row.included()?row.rank:null);put(m,"exclusion_reason",row.included()?null:row.exclusion());publicMembers.add(m);
   ObjectNode decision=decisions.addObject();decision.put("member_id",row.id);put(decision,"evidence_id",row.c.evidence()==null?null:row.c.evidence().id());decision.put("category",row.c.category());decision.put("high_value",row.c.highValue());decision.put("new_or_reanalysis_qualified",row.c.newOrReanalysisQualified());put(decision,"count_cutoff",row.c.countCutoff()==null?null:MICROS.format(row.c.countCutoff()));decision.put("included",row.included());put(decision,"exclusion_reason",row.included()?null:row.exclusion());
  }
  // The same validator used by the reader proves the full manifest before persisting it.
  ReportInputProfile.validated(root);
  String json=canonical(root);return new FrozenInput(json,CanonicalJson.hash(CanonicalJson.Domain.INPUT,root),coverage,List.copyOf(missingIds),hasContent,state);
 }

 private static final class Row {
  final Candidate c;final ObjectNode metadata;final String id;String reason;Long rank;
  Row(Candidate c,ObjectNode metadata,String id,String reason){this.c=c;this.metadata=metadata;this.id=id;this.reason=reason;}
  boolean included(){return reason==null&&(c.group()!=Group.HISTORICAL_HEAT||rank!=null);}
  String exclusion(){return reason!=null?reason:"not_in_top_five";}
 }
 private static Row row(Candidate c,Prepare command,Instant cutoff,LocalDate first,LocalDate last){
  if(c==null||c.group()==null||c.metadata()==null||c.metadata().resourceRef()==null||c.category()==null)throw invalid();
  if((command.stage()==Stage.DAILY_REPORT&&c.group()==Group.UPSTREAM_REPORTS)
      ||(command.stage()!=Stage.DAILY_REPORT&&c.group()!=Group.UPSTREAM_REPORTS))throw invalid();
  ObjectNode metadata=metadata(c);String id=CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,identity(metadata));String reason=c.exclusionReason();if(reason!=null&&reason.isBlank())throw invalid();
  Evidence e=c.evidence();
  if(e!=null){required(e.id());required(e.kind());if(e.references()==null||e.references().stream().anyMatch(v->v==null||v.isBlank())||e.bodyHash()==null||!e.bodyHash().matches("[0-9a-f]{64}"))throw invalid();
   if(c.metadata().materialHash()!=null&&!c.metadata().materialHash().equals(e.bodyHash())||c.metadata().businessDate()!=null&&!c.metadata().businessDate().equals(e.businessDate()))throw invalid();
   if(e.completedAt()!=null&&e.completedAt().getNano()%1000!=0)throw invalid();
  }
  if(c.group()==Group.HISTORICAL_HEAT&&("news_tech".equals(c.category()))&&(c.metadata().occurrenceCount()==null||!cutoff.equals(c.countCutoff())))throw new ProofUnavailable();
  if(reason==null){
   if(e==null||e.completedAt()==null||e.completedAt().isAfter(cutoff))reason="not_completed_at_cutoff";
   else if(!e.effectiveValid())reason="ineffective_version";
   else if(e.blocked())reason="blocked";
   else if(e.projectionDecision()!=null&&!Set.of("accepted","promoted").contains(e.projectionDecision()))reason="unresolved_projection";
   else if(e.businessDate()==null||e.businessDate().isAfter(command.businessDate())||c.group()!=Group.HISTORICAL_HEAT&&(e.businessDate().isBefore(first)||!e.businessDate().isBefore(last)))reason="outside_period";
   else if(c.group()==Group.HIGH_VALUE_ITEMS&&(!"news_tech".equals(c.category())||!c.highValue()))reason="not_high_value";
   else if(c.group()==Group.HISTORICAL_HEAT&&!"news_tech".equals(c.category()))reason="wrong_heat_category";
   else if(c.group()==Group.NEW_CATALOG_ANALYSIS&&(!Set.of("agent","skill","workflow","model").contains(c.category())||!c.newOrReanalysisQualified()))reason="not_new_or_reanalysis";
   else if(c.group()==Group.UPSTREAM_REPORTS&&(!"daily".equals(c.category())||!"report".equals(c.metadata().resourceRef().kind())||c.metadata().businessDate()==null))reason="not_daily_report";
  }
  if(e!=null&&"no_updates".equals(e.kind()))throw invalid();
  if(reason==null)CanonicalJson.parse(e.bodyJson());
  return new Row(c,metadata,id,reason);
 }
 private static void rank(List<Row> rows){
  var heat=rows.stream().filter(r->r.c.group()==Group.HISTORICAL_HEAT).toList();heat.forEach(r->r.rank=null);
  var eligible=heat.stream().filter(r->r.reason==null).sorted((a,b)->{int count=Long.compare(b.c.metadata().occurrenceCount(),a.c.metadata().occurrenceCount());return count!=0?count:utf8(a.c.metadata().resourceRef().id(),b.c.metadata().resourceRef().id());}).toList();
  for(int i=0;i<Math.min(5,eligible.size());i++)eligible.get(i).rank=(long)i+1;
 }
 private static LocalDate validateCommand(Prepare c,Instant cutoff){
  if(c==null||c.stage()==null||!c.stage().report()||c.businessDate()==null||cutoff==null||cutoff.getNano()%1000!=0||c.generation()<1||c.generation()>CanonicalJson.MAX_SAFE_INTEGER||c.settingsHash()==null||!c.settingsHash().matches("[0-9a-f]{64}"))throw invalid();required(c.logicalKey());
  LocalDate first=switch(c.stage()){case WEEKLY_REPORT->{if(c.businessDate().getDayOfWeek()!=DayOfWeek.SUNDAY)throw invalid();yield c.businessDate().minusDays(6);}case MONTHLY_REPORT->{if(c.businessDate().getDayOfMonth()!=c.businessDate().lengthOfMonth())throw invalid();yield c.businessDate().withDayOfMonth(1);}default->c.businessDate();};
  if(cutoff.isBefore(c.businessDate().plusDays(1).atStartOfDay(ZONE).toInstant()))throw invalid();return first;
 }
 private static ObjectNode metadata(Candidate c){
  Metadata x=c.metadata();ObjectNode m=JSON.createObjectNode();m.put("group",c.group().wire());ObjectNode ref=m.putObject("resource_ref");put(ref,"kind",x.resourceRef().kind());put(ref,"id",x.resourceRef().id());put(ref,"package_id",x.resourceRef().packageId());
  put(m,"revision",x.revision());put(m,"revision_id",x.revisionId());put(m,"material_hash",x.materialHash());put(m,"source_id",x.sourceId());put(m,"source_run_id",x.sourceRunId());put(m,"source_item_id",x.sourceItemId());put(m,"business_date",x.businessDate()==null?null:x.businessDate().toString());put(m,"title",x.title());put(m,"summary",x.summary());put(m,"occurrence_count",x.occurrenceCount());return m;
 }
 private static ObjectNode identity(ObjectNode m){ObjectNode id=JSON.createObjectNode();for(String f:List.of("group","resource_ref","revision","revision_id","material_hash","source_id","source_run_id","source_item_id","business_date"))id.set(f,m.get(f));return id;}
 private static ObjectNode missingNode(MissingInput x){if(x==null)throw invalid();required(x.kind());required(x.reason());ObjectNode m=JSON.createObjectNode();put(m,"kind",x.kind());put(m,"id",x.id());put(m,"business_date",x.businessDate()==null?null:x.businessDate().toString());put(m,"reason",x.reason());return m;}
 private static void addMissing(Map<String,MissingInput> out,MissingInput value){String key=canonical(missingNode(value));if(out.putIfAbsent(key,value)!=null)throw invalid();}
 private static void put(ObjectNode node,String key,Object value){if(value==null)node.putNull(key);else if(value instanceof Long n)node.put(key,n);else node.put(key,value.toString());}
 private static String canonical(JsonNode value){return new String(CanonicalJson.bytes(value),StandardCharsets.UTF_8);}
 private static int utf8(String a,String b){return Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));}
 private static void required(String value){if(value==null||value.isBlank())throw invalid();}
 private static IllegalArgumentException invalid(){return new IllegalArgumentException("invalid report input proof");}
}
