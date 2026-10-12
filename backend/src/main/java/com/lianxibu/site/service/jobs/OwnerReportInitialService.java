package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.content.*;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import tools.jackson.databind.JsonNode;

/** Initial POST only obtains generation one. Complete input preparation and model dispatch remain scheduled. */
public final class OwnerReportInitialService {
 private final JdbcInitialReportIntent intents;private final JdbcReportInitialActions actions;
 private final ReportPreparedInputService originals;private final SharedWriteExecutor writes;private final JdbcWorkerState worker;
 private final Clock clock;private final BooleanSupplier allowed;
 public OwnerReportInitialService(DataSource source,JdbcInitialReportIntent intents,JdbcReportInitialActions actions,ReportPreparedInputService originals,SharedWriteExecutor writes,Clock clock,BooleanSupplier allowed){worker=new JdbcWorkerState(Objects.requireNonNull(source));this.intents=Objects.requireNonNull(intents);this.actions=Objects.requireNonNull(actions);this.originals=Objects.requireNonNull(originals);this.writes=Objects.requireNonNull(writes);this.clock=Objects.requireNonNull(clock);this.allowed=Objects.requireNonNull(allowed);}
 public ContentService.Job generationIntent(Actor actor,ContentService.GenerationIntentWrite request,String key,String csrf,String origin){
  owner(actor);if(key==null||!key.matches("[!-~]{1,128}")||csrf==null||csrf.isBlank()||origin==null||origin.isBlank()||request==null||!"initial".equals(request.mode())||request.intentRevision()!=null||request.reason()==null||request.reason().isBlank()||request.reason().codePointCount(0,request.reason().length())>2048)throw invalid();
  var command=new JdbcReportInitialActions.Command(scope(request),actor.userId(),request.reason());String action=JdbcReportInitialActions.action(key);
  try{
   var accepted=writes.executePrepared(new SharedWriteExecutor.ActionContext(action,JdbcReportInitialActions.operation(action),null),k->actions.read(k,command),()->{
    if(!allowed.getAsBoolean())throw unavailable();Instant at=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    return context->{
     if(!allowed.getAsBoolean())throw unavailable();var initial=intents.ensureInWriter(command.scope(),at);String coverage=null;
     if(initial.cutoff()!=null){var whole=originals.lookup(new ReportInputCaptureService.Scope(command.scope().logicalKey(),1,GenerationFlowService.Stage.valueOf(command.scope().type().toUpperCase(Locale.ROOT)),command.scope().day()));
      if(whole==null||!initial.cutoff().equals(whole.input().cutoff())||!initial.coverage().equals(whole.input().coverageState()))throw unavailable();
      var snapshot=CanonicalJson.parse(whole.input().snapshotJson());var complete=ReportPreparationInputProfile.complete(snapshot);if(complete.command().explicitSupplement())throw unavailable();
      coverage=JdbcFrozenInputs.canonical(snapshot.get(ReportInputProfile.FIELD).get("coverage"));
     }
     var slot=worker.slot();if(slot.id()!=1||slot.fencing()<0||slot.fencing()>CanonicalJson.MAX_SAFE_INTEGER)throw unavailable();
     var result=new JdbcReportInitialActions.Accepted(context.preallocatedResourceId(),initial,coverage,slot.state(),slot.revision(),slot.attempt());
     actions.append(action,command,result,at);return result;
    };
   });return job(accepted);
  }catch(IdentityService.Rejected refusal){throw refusal;}catch(JdbcReportInitialActions.Conflict conflict){throw new IdentityService.Rejected(IdentityService.Refusal.IDENTITY_CONFLICT,null);}catch(RuntimeException failure){throw unavailable();}
 }
 private static JdbcInitialReportIntent.Scope scope(ContentService.GenerationIntentWrite request){
  if(request.kind()==null||!Set.of("daily","weekly","monthly").contains(request.kind())||request.periodStart()==null||request.periodEnd()==null||!"Asia/Shanghai".equals(request.timezone()))throw invalid();
  var zone=ZoneId.of("Asia/Shanghai");LocalDate day=request.periodEnd().atZone(zone).toLocalDate().minusDays(1);var first=switch(request.kind()){case "daily"->day;case "weekly"->day.minusDays(6);case "monthly"->day.withDayOfMonth(1);default->throw invalid();};
  if(!first.atStartOfDay(zone).toInstant().equals(request.periodStart())||!day.plusDays(1).atStartOfDay(zone).toInstant().equals(request.periodEnd()))throw invalid();
  try{return new JdbcInitialReportIntent.Scope("report:"+request.kind()+":"+day,request.kind()+"_report",day);}catch(RuntimeException error){throw invalid();}
 }
 private static ContentService.Job job(JdbcReportInitialActions.Accepted accepted){
  var intent=accepted.intent();ContentService.Coverage coverage=new ContentService.Coverage("partial",List.of(new ContentService.MissingInput("input_snapshot",intent.id(),null,"not_frozen")),List.of(),List.of());
  if(accepted.coverageJson()!=null){var value=CanonicalJson.parse(accepted.coverageJson());var missing=new ArrayList<ContentService.MissingInput>();for(var item:value.get("missing_inputs"))missing.add(new ContentService.MissingInput(WireValues.text(item.get("kind")),item.get("id").isNull()?null:WireValues.id(item.get("id"),false),item.get("business_date").isNull()?null:WireValues.businessDate(item.get("business_date")),WireValues.text(item.get("reason"))));coverage=new ContentService.Coverage(WireValues.text(value.get("state")),List.copyOf(missing),dates(value.get("missing_dates")),dates(value.get("no_update_dates")));}
  return new ContentService.Job(intent.id(),"generation_intent",intent.revision(),intent.scope().type(),0,intent.state(),intent.id(),intent.revision(),JdbcFrozenInputs.stableId("report-content",intent.scope().logicalKey()),null,intent.inputState(),null,intent.cutoff(),coverage,null,0,null,0,false,accepted.slotState(),accepted.slotRevision(),accepted.occupiedAttemptId(),null,null,null,List.of(),intent.state().equals("waiting_input")?List.of("waiting_input"):List.of());
 }
 private static List<LocalDate> dates(JsonNode node){var values=new ArrayList<LocalDate>();for(var value:node)values.add(WireValues.businessDate(value));return List.copyOf(values);}
 private static void owner(Actor actor){if(actor==null||actor.userId()==null||actor.userId().isBlank())throw new IdentityService.Rejected(IdentityService.Refusal.UNAUTHENTICATED,null);if(!"owner".equals(actor.role()))throw new IdentityService.Rejected(IdentityService.Refusal.FORBIDDEN,null);}
 private static IllegalArgumentException invalid(){return new IllegalArgumentException("invalid initial report request");}
 private static IdentityService.Rejected unavailable(){return new IdentityService.Rejected(IdentityService.Refusal.TEMPORARILY_UNAVAILABLE,"1");}
 @Override public String toString(){return "OwnerReportInitialService[redacted]";}
}
