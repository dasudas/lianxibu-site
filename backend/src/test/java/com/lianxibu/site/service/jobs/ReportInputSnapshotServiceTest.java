package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.service.content.ReportInputProfile;
import com.lianxibu.site.service.jobs.GenerationFlowService.*;
import com.lianxibu.site.service.jobs.ReportInputSnapshotService.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class ReportInputSnapshotServiceTest {
 static final LocalDate DAY=LocalDate.of(2026,1,1);
 static final Instant CUTOFF=Instant.parse("2026-01-01T21:00:00Z");
 static final String HASH="a".repeat(64);
 static Prepare command(Stage stage,LocalDate day){return new Prepare("fictional-report",2,stage,day,null,List.of(),List.of(),List.of(),false,HASH);}
 static Candidate candidate(Group group,String id,LocalDate day,long count) {
  String category=group==Group.SOURCE_SUMMARIES?"source":group==Group.NEW_CATALOG_ANALYSIS?"agent":group==Group.UPSTREAM_REPORTS?"daily":"news_tech";
  String kind=group==Group.UPSTREAM_REPORTS?"report":"article_revision";
  var meta=new Metadata(new ResourceRef(kind,id,null),1L,"fictional-revision-"+id,HASH,"fictional-source","fictional-run","fictional-item-"+id,day,"Fictional "+id,"Fictional summary",group==Group.HISTORICAL_HEAT?count:null);
  var evidence=new Evidence("fictional-evidence-"+id,group==Group.UPSTREAM_REPORTS?"daily_report":"analysis",day,CUTOFF.minusSeconds(10),HASH,"{\"fixture\":\""+id+"\"}",true,false,"accepted",List.of());
  return new Candidate(group,meta,evidence,category,true,true,group==Group.HISTORICAL_HEAT?CUTOFF:null,null);
 }
 static Candidate evidence(Candidate c,Evidence e){return new Candidate(c.group(),c.metadata(),e,c.category(),c.highValue(),c.newOrReanalysisQualified(),c.countCutoff(),c.exclusionReason());}
 static SourceProof proof(Prepare c,Instant cutoff,List<Candidate> candidates,List<LocalDate> empty){
  LocalDate start=switch(c.stage()){case WEEKLY_REPORT->c.businessDate().minusDays(6);case MONTHLY_REPORT->c.businessDate().withDayOfMonth(1);default->c.businessDate();};
  return new SourceProof(cutoff,start,c.businessDate().plusDays(1),true,candidates,List.of(),empty,List.of());
 }
 static FrozenInput freeze(Prepare c,Instant cutoff,List<Candidate> candidates,List<LocalDate> empty){return new ReportInputSnapshotService((q,t)->proof(q,t,candidates,empty)).freeze(c,cutoff);}
 static JsonNode profile(FrozenInput frozen){return CanonicalJson.parse(frozen.snapshotJson()).get(ReportInputProfile.FIELD);}
 static JsonNode member(JsonNode p,String id){for(var m:p.get("members"))if(m.get("resource_ref").get("id").asText().equals(id))return m;throw new AssertionError(id);}

 @Test void freezesFourGroupsFullExclusionsAndHistoricalCutoffTopFive() {
  var candidates=new ArrayList<Candidate>();candidates.add(candidate(Group.SOURCE_SUMMARIES,"source",DAY,0));
  candidates.add(candidate(Group.HIGH_VALUE_ITEMS,"high",DAY,0));
  var low=candidate(Group.HIGH_VALUE_ITEMS,"low",DAY,0);candidates.add(new Candidate(low.group(),low.metadata(),low.evidence(),low.category(),false,true,null,null));
  candidates.add(candidate(Group.NEW_CATALOG_ANALYSIS,"new",DAY,0));
  var old=candidate(Group.NEW_CATALOG_ANALYSIS,"old",DAY,0);candidates.add(new Candidate(old.group(),old.metadata(),old.evidence(),old.category(),true,false,null,null));
  for(int i=0;i<7;i++)candidates.add(candidate(Group.HISTORICAL_HEAT,"heat-"+i,DAY.minusDays(40),100-i));
  var blocked=candidate(Group.HISTORICAL_HEAT,"blocked",DAY.minusDays(100),1000);var e=blocked.evidence();
  candidates.add(evidence(blocked,new Evidence(e.id(),e.kind(),e.businessDate(),e.completedAt(),e.bodyHash(),e.bodyJson(),true,true,"accepted",List.of())));
  FrozenInput frozen=freeze(command(Stage.DAILY_REPORT,DAY),CUTOFF,candidates,List.of());var p=profile(frozen);
  assertEquals("queued",frozen.state());assertTrue(frozen.hasContent());assertEquals("complete",frozen.coverageState());assertEquals(13,p.get("total").asInt());assertEquals(4,p.get("group_counts").size());
  for(int i=0;i<5;i++){var m=member(p,"heat-"+i);assertTrue(m.get("included").asBoolean());assertEquals(i+1,m.get("rank").asInt());assertEquals(100-i,m.get("occurrence_count").asInt());}
  assertEquals("not_in_top_five",member(p,"heat-5").get("exclusion_reason").asText());assertTrue(member(p,"heat-5").get("rank").isNull());
  assertEquals("blocked",member(p,"blocked").get("exclusion_reason").asText());assertEquals("not_high_value",member(p,"low").get("exclusion_reason").asText());assertEquals("not_new_or_reanalysis",member(p,"old").get("exclusion_reason").asText());
  assertEquals(frozen.inputHash(),CanonicalJson.hash(CanonicalJson.Domain.INPUT,CanonicalJson.parse(frozen.snapshotJson())));
  assertNotNull(ReportInputProfile.validated(CanonicalJson.parse(frozen.snapshotJson())));
  assertEquals(8,CanonicalJson.parse(frozen.snapshotJson()).get("members").size());
  Collections.reverse(candidates);assertEquals(frozen,freeze(command(Stage.DAILY_REPORT,DAY),CUTOFF,candidates,List.of()));
 }
 @Test void tieUsesUtf8StableResourceIdentityAndKnownZeroIsKept() {
  var candidates=new ArrayList<Candidate>();candidates.add(candidate(Group.SOURCE_SUMMARIES,"source",DAY,0));
  for(String id:List.of("z","a","b","c","d","e"))candidates.add(candidate(Group.HISTORICAL_HEAT,id,DAY.minusDays(1),0));
  var p=profile(freeze(command(Stage.DAILY_REPORT,DAY),CUTOFF,candidates,List.of()));assertEquals(1,member(p,"a").get("rank").asInt());assertEquals(5,member(p,"e").get("rank").asInt());assertFalse(member(p,"z").get("included").asBoolean());assertEquals(0,member(p,"a").get("occurrence_count").asInt());
 }
 @Test void onlyHeatNeverQueuesAndProvenEmptyHasProfileWithoutFakeMissingFacts() {
  var heat=freeze(command(Stage.DAILY_REPORT,DAY),CUTOFF,List.of(candidate(Group.HISTORICAL_HEAT,"heat",DAY.minusDays(1),10)),List.of());
  assertFalse(heat.hasContent());assertEquals("no_input",heat.state());assertEquals("no_input",heat.coverageState());assertEquals(0,CanonicalJson.parse(heat.snapshotJson()).get("members").size());assertEquals("no_daily_input",member(profile(heat),"heat").get("exclusion_reason").asText());
  var empty=freeze(command(Stage.DAILY_REPORT,DAY),CUTOFF,List.of(),List.of());assertNotNull(empty.inputHash());assertEquals(0,profile(empty).get("total").asInt());
  var noUpdates=freeze(command(Stage.DAILY_REPORT,DAY),CUTOFF,List.of(),List.of(DAY));assertEquals("coverage_only",noUpdates.state());assertEquals("complete",noUpdates.coverageState());assertEquals(1,profile(noUpdates).get("coverage").get("no_update_dates").size());
 }
 @Test void weeklyAndMonthlyUseExactDailyPeriodWithSeparateNoUpdateAndMissingDates() {
  for(Stage stage:List.of(Stage.WEEKLY_REPORT,Stage.MONTHLY_REPORT)){
   LocalDate end=stage==Stage.WEEKLY_REPORT?LocalDate.of(2026,1,11):LocalDate.of(2026,1,31);LocalDate start=stage==Stage.WEEKLY_REPORT?end.minusDays(6):end.withDayOfMonth(1);Instant cutoff=end.plusDays(1).atTime(5,0).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
   var c=candidate(Group.UPSTREAM_REPORTS,"daily",start,0);var f=freeze(command(stage,end),cutoff,List.of(c),List.of(start.plusDays(1)));var p=profile(f);
   assertEquals("partial",f.coverageState());assertEquals(1,p.get("group_counts").size());assertEquals("report",member(p,"daily").get("resource_ref").get("kind").asText());assertEquals(stage==Stage.WEEKLY_REPORT?5:29,p.get("coverage").get("missing_dates").size());assertEquals(1,p.get("coverage").get("no_update_dates").size());
   assertNotNull(ReportInputProfile.validated(CanonicalJson.parse(f.snapshotJson())));
  }
 }
 @Test void dependenciesPropagateBeforeHeatRankingAndSelectedBodiesAreFrozen() {
  Candidate a=candidate(Group.SOURCE_SUMMARIES,"source",DAY,0);var e=a.evidence();a=evidence(a,new Evidence(e.id(),e.kind(),e.businessDate(),e.completedAt(),e.bodyHash(),e.bodyJson(),true,false,"accepted",List.of("fictional-dependency")));
  Candidate b=candidate(Group.HIGH_VALUE_ITEMS,"child",DAY,0);var child=b.evidence();b=evidence(b,new Evidence(child.id(),child.kind(),child.businessDate(),child.completedAt(),child.bodyHash(),child.bodyJson(),true,false,"accepted",List.of(e.id())));
  var c=command(Stage.DAILY_REPORT,DAY);var missing=freeze(c,CUTOFF,List.of(a,b),List.of());assertEquals("missing_dependencies",member(profile(missing),"child").get("exclusion_reason").asText());assertFalse(missing.hasContent());
  var candidates=List.of(a,b);var service=new ReportInputSnapshotService((q,t)->{var p=proof(q,t,candidates,List.of());return new SourceProof(p.cutoff(),p.periodStart(),p.periodEnd(),true,p.candidates(),p.missingInputs(),p.noUpdateDates(),List.of("fictional-dependency"));});
  var good=service.freeze(c,CUTOFF);assertTrue(good.hasContent());assertEquals(2,CanonicalJson.parse(good.snapshotJson()).get("members").size());assertEquals("source",CanonicalJson.parse(good.snapshotJson()).get("members").get(1).get("body").get("fixture").asText());
 }
 @Test void rejectsIncompleteUniverseWrongPeriodCutoffAndUnprovenHeatCounts() {
  var c=command(Stage.DAILY_REPORT,DAY);var original=proof(c,CUTOFF,List.of(),List.of());
  for(var bad:List.of(new SourceProof(CUTOFF,DAY,DAY.plusDays(1),false,List.of(),List.of(),List.of(),List.of()),new SourceProof(CUTOFF.plusSeconds(1),DAY,DAY.plusDays(1),true,List.of(),List.of(),List.of(),List.of()),new SourceProof(CUTOFF,DAY.minusDays(1),DAY.plusDays(1),true,List.of(),List.of(),List.of(),List.of())))assertThrows(ProofUnavailable.class,()->new ReportInputSnapshotService((q,t)->bad).freeze(c,CUTOFF));
  assertThrows(ProofUnavailable.class,()->new ReportInputSnapshotService((q,t)->null).freeze(c,CUTOFF));
  var heat=candidate(Group.HISTORICAL_HEAT,"heat",DAY,1);var bad=new Candidate(heat.group(),heat.metadata(),heat.evidence(),heat.category(),true,true,CUTOFF.plusSeconds(1),null);assertThrows(ProofUnavailable.class,()->freeze(c,CUTOFF,List.of(bad),List.of()));
  assertThrows(IllegalArgumentException.class,()->freeze(c,CUTOFF.plusNanos(1),List.of(),List.of()));
 }
 @Test void rejectsDuplicateIdentityConflictingBodiesAndAmbiguousDailyVersions() {
  var a=candidate(Group.SOURCE_SUMMARIES,"same",DAY,0);assertThrows(IllegalArgumentException.class,()->freeze(command(Stage.DAILY_REPORT,DAY),CUTOFF,List.of(a,a),List.of()));
  var b=candidate(Group.HIGH_VALUE_ITEMS,"different",DAY,0);var e=b.evidence();b=evidence(b,new Evidence(a.evidence().id(),e.kind(),e.businessDate(),e.completedAt(),e.bodyHash(),e.bodyJson(),true,false,"accepted",List.of()));var conflict=List.of(a,b);assertThrows(IllegalArgumentException.class,()->freeze(command(Stage.DAILY_REPORT,DAY),CUTOFF,conflict,List.of()));
  var weekly=command(Stage.WEEKLY_REPORT,LocalDate.of(2026,1,4));var two=List.of(candidate(Group.UPSTREAM_REPORTS,"v1",DAY,0),candidate(Group.UPSTREAM_REPORTS,"v2",DAY,0));assertThrows(ProofUnavailable.class,()->freeze(weekly,CUTOFF.plusSeconds(4*86400),two,List.of()));
 }
 @Test void preservesUnknownPublicMetadataAndNeverDerivesSummaryFromBody() {
  var a=candidate(Group.SOURCE_SUMMARIES,"source",DAY,0);var m=a.metadata();var nullable=new Metadata(m.resourceRef(),null,null,null,null,null,null,null,null,null,null);var c=new Candidate(a.group(),nullable,a.evidence(),a.category(),true,true,null,null);
  var p=profile(freeze(command(Stage.DAILY_REPORT,DAY),CUTOFF,List.of(c),List.of()));var member=member(p,"source");for(String field:List.of("revision","material_hash","source_id","business_date","title","summary","occurrence_count"))assertTrue(member.get(field).isNull(),field);
  assertFalse(p.toString().contains("fixture"));assertThrows(IllegalArgumentException.class,()->freeze(command(Stage.DAILY_REPORT,DAY),CUTOFF,List.of(c),List.of(DAY)));
 }
 @Test void completionCutoffValidityAndProjectionFailuresStayExcludedWithoutRawBodies() {
  var candidates=new ArrayList<Candidate>();candidates.add(candidate(Group.SOURCE_SUMMARIES,"source",DAY,0));
  for(String fault:List.of("late","invalid","projection")){var c=candidate(Group.HIGH_VALUE_ITEMS,fault,DAY,0);var e=c.evidence();candidates.add(evidence(c,new Evidence(e.id(),e.kind(),e.businessDate(),fault.equals("late")?CUTOFF.plusSeconds(1):e.completedAt(),e.bodyHash(),"{\"fictional_excluded_body\":true}",!fault.equals("invalid"),false,fault.equals("projection")?"pending":"accepted",List.of())));}
  var frozen=freeze(command(Stage.DAILY_REPORT,DAY),CUTOFF,candidates,List.of());assertEquals("partial",frozen.coverageState());assertEquals(1,CanonicalJson.parse(frozen.snapshotJson()).get("members").size());assertFalse(frozen.snapshotJson().contains("fictional_excluded_body"));assertEquals(3,profile(frozen).get("coverage").get("missing_inputs").size());
 }
 @Test void rejectsDuplicateHeatEntitiesAndDependencyProofAliasingAnExcludedCandidate() {
  var a=candidate(Group.HISTORICAL_HEAT,"same",DAY.minusDays(1),10);var m=a.metadata();
  var revised=new Metadata(m.resourceRef(),2L,"fictional-second-revision",m.materialHash(),m.sourceId(),m.sourceRunId(),m.sourceItemId(),m.businessDate(),m.title(),m.summary(),10L);
  var b=new Candidate(a.group(),revised,a.evidence(),a.category(),true,true,CUTOFF,null);
  assertThrows(ProofUnavailable.class,()->freeze(command(Stage.DAILY_REPORT,DAY),CUTOFF,List.of(a,b),List.of()));
  var c=command(Stage.DAILY_REPORT,DAY);var source=candidate(Group.SOURCE_SUMMARIES,"source",DAY,0);
  assertThrows(IllegalArgumentException.class,()->new ReportInputSnapshotService((q,t)->new SourceProof(t,DAY,DAY.plusDays(1),true,List.of(source),List.of(),List.of(),List.of(source.evidence().id()))).freeze(c,CUTOFF));
 }
 @Test void sourceIsReadOnceAndInvalidCommandDoesNotRead() {
  int[] reads={0};var service=new ReportInputSnapshotService((c,t)->{reads[0]++;return proof(c,t,List.of(),List.of());});service.freeze(command(Stage.DAILY_REPORT,DAY),CUTOFF);assertEquals(1,reads[0]);
  assertThrows(IllegalArgumentException.class,()->service.freeze(command(Stage.AD_HOC_AI,DAY),CUTOFF));assertEquals(1,reads[0]);
 }
}
