package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.content.ContentReadService;
import com.lianxibu.site.service.jobs.ResultApplicationService.*;
import java.time.*;
import java.util.*;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

/** Makes completed daily versions discoverable without preparing a future report early. */
public final class ReportResultDownstream implements ContentResultHandler.Downstream {
 private final JdbcFrozenInputs inputs;private final JdbcReportResults results;private final ContentReadService contents;private final Clock clock;
 public ReportResultDownstream(JdbcFrozenInputs inputs,JdbcReportResults results,ContentReadService contents,Clock clock){this.inputs=Objects.requireNonNull(inputs);this.results=Objects.requireNonNull(results);this.contents=Objects.requireNonNull(contents);this.clock=Objects.requireNonNull(clock);}
 @Override public void afterApplied(Input input,FrozenApplicationContext context,Validated result,Applied applied){
  JdbcFrozenInputs.requireTx();if(input==null||context==null||result==null||applied==null||!input.stage().report()||!"report".equals(result.kind())||!context.referencesComplete()||!Objects.equals(context.contentId(),applied.contentId())||applied.contentRevision()==null||applied.contentRevision()<1)throw invalid();
  var original=FrozenApplicationContext.fromJson(CanonicalJson.parse(inputs.read(input.rootId(),input.inputHash())).get(FrozenApplicationContext.FIELD));
  if(!original.json().equals(context.json())||!input.equals(original.input(input.rootId(),input.attemptId(),input.inputHash(),input.expectedRootRevision())))throw invalid();
  var envelope=JsonMapper.shared().createObjectNode().put("schema_version","1").put("input_hash",result.inputHash()).put("result_hash",result.resultHash());envelope.set("payload",CanonicalJson.parse(result.payloadJson()));if(!result.equals(ResultApplicationService.validate(input,envelope.toString())))throw invalid();
  var base=JsonMapper.shared().createObjectNode().put("schema_version","report-result-ready-v1").put("root_id",input.rootId()).put("input_hash",input.inputHash()).put("result_hash",result.resultHash()).put("stage",input.stage().name().toLowerCase(Locale.ROOT)).put("generation",input.generation()).put("content_id",applied.contentId()).put("applied_revision",applied.contentRevision()).put("period_start",context.periodStart()).put("period_end",context.periodEnd());
  var old=results.read(input.rootId());if(old!=null){if(!old.isObject()||old.size()!=base.size()+2||!old.has("content_version")||!old.has("intent_ids"))throw invalid();var prior=((ObjectNode)old).deepCopy();prior.remove("content_version");prior.remove("intent_ids");if(!JdbcFrozenInputs.canonical(base).equals(JdbcFrozenInputs.canonical(prior)))throw invalid();var version=ContentVersionKey.fromJson(old.get("content_version"));if(!"report".equals(version.contentKind())||!context.contentId().equals(version.contentId())||!input.rootId().equals(version.inputJobId())||!CanonicalJson.hash(CanonicalJson.Domain.CONTENT,envelope.get("payload").get("body")).equals(version.bodyHash()))throw invalid();return;}
  var current=contents.administrativeVersion("report",context.contentId()).orElseThrow(ReportResultDownstream::invalid);var body=envelope.get("payload").get("body");String hash=CanonicalJson.hash(CanonicalJson.Domain.CONTENT,body);
  if(current.revision()!=applied.contentRevision()||!"draft".equals(current.state())||!input.rootId().equals(current.inputJobId())||current.bodyHash()==null||!hash.equals(HexFormat.of().formatHex(current.bodyHash()))||!Arrays.equals(CanonicalJson.bytes(body),CanonicalJson.bytes(CanonicalJson.parse(current.bodyJson()))))throw invalid();
  var version=new ContentVersionKey("report",context.contentId(),current.bodyRevision(),hash,input.rootId());base.set("content_version",CanonicalJson.parse(JdbcFrozenContentVersions.version(version)));var intentIds=base.putArray("intent_ids");var now=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  if(input.stage()==GenerationFlowService.Stage.DAILY_REPORT){LocalDate day=Instant.parse(context.periodEnd()).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().minusDays(1);var periods=periods(day);intentIds.add(results.ensurePeriod("weekly",periods.get(0),now));intentIds.add(results.ensurePeriod("monthly",periods.get(1),now));}
  results.save(input.rootId(),base,now);
 }
 static List<LocalDate> periods(LocalDate day){Objects.requireNonNull(day);return List.of(day.with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)),day.withDayOfMonth(day.lengthOfMonth()));}
 private static Rejected invalid(){return new Rejected();}
}
