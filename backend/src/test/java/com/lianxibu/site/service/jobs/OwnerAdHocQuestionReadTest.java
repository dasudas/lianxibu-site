package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.repository.content.JdbcContentReads;
import com.lianxibu.site.service.content.ContentReadService;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual original acceptance/prepare/claim/send/result ledger, no network model. */
class OwnerAdHocQuestionReadTest {
 TimeZone originalZone;OwnerAdHocCreationTest c;DataSourceTransactionManager tx;JdbcOwnerAdHocCreation.Accepted accepted;int sends;
 OwnerAdHocCreationTest fixtureSource(){return new OwnerAdHocCreationTest();}
 boolean mysql(){return false;}
 @BeforeEach void setup(){
  if(originalZone==null)originalZone=TimeZone.getDefault();if(!mysql())TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
  c=fixtureSource();c.setup();if(!mysql()){var ds=(org.h2.jdbcx.JdbcDataSource)c.f.source;ds.setURL(ds.getURL()+";TIME ZONE=UTC");}tx=new DataSourceTransactionManager(c.f.source);String time=mysql()?"DATETIME(6)":"TIMESTAMP";
  for(String column:List.of("worker_instance_id VARCHAR(64)","dispatch_token BIGINT","request_deadline "+time,"send_authorized_at "+time,"slot_released_at "+time,"release_reason VARCHAR(2048)","result_hash BINARY(32)","result_json "+(mysql()?"JSON":"CLOB"),"application_state VARCHAR(32)","report_mode VARCHAR(32)"))c.f.jdbc.execute("ALTER TABLE jobs ADD "+column);
  c.f.jdbc.execute("ALTER TABLE worker_lock ADD request_deadline "+time);
  if(!mysql()){c.f.jdbc.execute("CREATE ALIAS JSON_EXTRACT FOR 'com.lianxibu.site.service.jobs.TransportUnknownServiceTest.extract'");c.f.jdbc.execute("CREATE ALIAS JSON_UNQUOTE FOR 'com.lianxibu.site.service.jobs.TransportUnknownServiceTest.unquote'");c.f.jdbc.execute("CREATE ALIAS HEX FOR 'com.lianxibu.site.service.jobs.ResultJdbcStoreTest.hex'");c.f.jdbc.execute("CREATE ALIAS UNHEX FOR 'com.lianxibu.site.service.jobs.ResultJdbcStoreTest.unhex'");}
  accepted=c.first();
 }
 @AfterEach void restoreFixtureTimezone(){if(originalZone!=null)TimeZone.setDefault(originalZone);originalZone=null;}
 OwnerAdHocQuestionReadService service(){return new OwnerAdHocQuestionReadService(c.f.source,tx);}
 OwnerAdHocQuestionReadService.View read(){return service().get(c.owner,accepted.command().intentId());}
 GenerationFlowService.Frozen prepare(){return new OwnerAdHocPreparationService(c.f.source,c.accepted,c.f.writes,tx,c.f.clock,()->c.allowed).prepare(accepted.actionKey());}
 @Test void ExactOriginalQuestionRemainsAfterGateClosesAndARealRestart(){var before=new JdbcWorkerState(c.f.source).slot();int rows=c.f.count("1=1");c.allowed=false;var first=read();assertEquals(accepted.command().prompt(),first.originalRequest().prompt());assertEquals(accepted.command().intentId(),first.intent().id());assertNull(first.preparedJob());assertNull(first.retainedResult());assertEquals(first,read());assertEquals(rows,c.f.count("1=1"));assertEquals(before,new JdbcWorkerState(c.f.source).slot());assertEquals(1,c.freezes);assertFalse(first.toString().contains(first.originalRequest().prompt()));}
 @Test void PreparedOriginalIsLinkedWithoutCreatingAnythingOrConsumingBudget(){prepare();int rows=c.f.count("1=1");var view=read();assertEquals(accepted.command().rootId(),view.preparedJob().id());assertEquals(view.intent().id(),view.preparedJob().intentId());assertEquals(14,view.preparedJob().priority());assertEquals(0,view.preparedJob().callCount());assertEquals(3,view.preparedJob().maxAttempts());assertNull(view.retainedResult());assertEquals(rows,c.f.count("1=1"));assertEquals(0,sends);}
 @Test void MissingPrepareReceiptOrDamagedOriginalInputMustNotBecomeNull(){prepare();c.f.jdbc.update("DELETE FROM jobs WHERE target_type='generation_prepare'");assertUnavailable();setup();prepare();c.f.jdbc.update("UPDATE jobs SET payload_hash=? WHERE purpose='input'",new byte[32]);assertUnavailable();setup();prepare();c.f.jdbc.update("UPDATE jobs SET request_json=REPLACE(request_json,'Fictional original question','Fictional wrong question') WHERE record_kind='ai_attempt'");assertUnavailable();}
 @Test void AbsentWrongRecordKindsAndOtherStagesAreNotQuestions(){assertEquals(IdentityService.Refusal.NOT_FOUND,assertThrows(IdentityService.Rejected.class,()->service().get(c.owner,"fictional-absent")).refusal());prepare();assertEquals(IdentityService.Refusal.NOT_FOUND,assertThrows(IdentityService.Rejected.class,()->service().get(c.owner,accepted.command().rootId())).refusal());c.f.jdbc.update("UPDATE jobs SET task_type='daily_report' WHERE record_kind='generation_intent'");assertEquals(IdentityService.Refusal.NOT_FOUND,assertThrows(IdentityService.Rejected.class,this::read).refusal());}
 @Test void UnauthorizedReadersAndOuterTransactionsCannotTouchStorage(){c.f.jdbc.execute("DROP TABLE jobs");var service=service();assertEquals(IdentityService.Refusal.UNAUTHENTICATED,assertThrows(IdentityService.Rejected.class,()->service.get(null,null)).refusal());assertEquals(IdentityService.Refusal.FORBIDDEN,assertThrows(IdentityService.Rejected.class,()->service.get(new Actor("fictional-member","member"),null)).refusal());assertThrows(IllegalArgumentException.class,()->service.get(c.owner,"\n"));var reads=new TransactionTemplate(tx);reads.setReadOnly(true);assertEquals(IdentityService.Refusal.TEMPORARILY_UNAVAILABLE,reads.execute(s->assertThrows(IdentityService.Rejected.class,()->service.get(c.owner,accepted.command().intentId())).refusal()));}
 @Test void FullNativeAnswerSurvivesRestartAndLaterDiscardWithNoPublicContentOrSecondSend(){answer();var view=read();assertEquals("Fictional retained answer",view.retainedResult().payload().path("title").asText());assertEquals(1,view.preparedJob().callCount());assertEquals(2,view.preparedJob().remainingAttempts());assertEquals(view,read());var slot=new JdbcWorkerState(c.f.source).slot();c.f.jdbc.update("UPDATE jobs SET state='discarded',application_state='DISCARDED',revision=revision+1 WHERE id=?",accepted.command().rootId());assertEquals(view.retainedResult(),read().retainedResult());assertEquals(slot,new JdbcWorkerState(c.f.source).slot());assertEquals(1,sends);}
 @Test void RetainedHashChunkClaimAndSendAuthorizationAllRemainMandatory(){answer();c.f.jdbc.update("UPDATE jobs SET payload_hash=? WHERE purpose='result'",new byte[32]);assertUnavailable();setup();answer();c.f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='worker_claim'",new byte[32]);assertUnavailable();setup();answer();c.f.jdbc.update("UPDATE jobs SET send_authorized_at=NULL WHERE record_kind='ai_attempt'");assertUnavailable();}
 @Test void AlteredRetainedPayloadAndDamagedAcceptanceCannotBecomeAnAnswer(){answer();c.f.jdbc.update("UPDATE jobs SET payload_json=REPLACE(payload_json,'Fictional retained answer','Fictional altered answer') WHERE purpose='result'");assertUnavailable();setup();c.f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='owner_ad_hoc_create'",new byte[32]);assertUnavailable();}
 @Test void ConstructionHasNoDatabaseSettingsOrGateWork(){var source=org.mockito.Mockito.mock(javax.sql.DataSource.class);assertNotNull(new OwnerAdHocQuestionReadService(source,new DataSourceTransactionManager(source)));org.mockito.Mockito.verifyNoInteractions(source);}
 @Test void ChangedAcceptanceBetweenCaptureAndFinalReadCannotReturnTheCapturedQuestion(){var manager=new DataSourceTransactionManager(c.f.source){int begins;@Override protected void doBegin(Object transaction,org.springframework.transaction.TransactionDefinition definition){if(++begins==2)c.f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='owner_ad_hoc_create'",new byte[32]);super.doBegin(transaction,definition);}};assertEquals(IdentityService.Refusal.TEMPORARILY_UNAVAILABLE,assertThrows(IdentityService.Rejected.class,()->new OwnerAdHocQuestionReadService(c.f.source,manager).get(c.owner,accepted.command().intentId())).refusal());assertEquals(0,c.f.count("record_kind='logical_task'"));}
 void assertUnavailable(){assertEquals(IdentityService.Refusal.TEMPORARILY_UNAVAILABLE,assertThrows(IdentityService.Rejected.class,this::read).refusal());}
 void answer(){
  var frozen=prepare();c.f.clock=Clock.fixed(frozen.eligibleAt().plusSeconds(1),ZoneOffset.UTC);
  var worker=new WorkerAuthorizationService(c.f.writes,new WorkerJdbcStore(new JdbcWorkerState(c.f.source),new JdbcJobRecords(c.f.source),"fictional-read-worker",()->false,id->true,c.f.clock),c.f.clock,()->true);
  var action=new SharedWriteExecutor.ActionContext("fictional-read-claim",accepted.command().rootId(),frozen.inputHash());var claim=worker.claim(action);assertEquals(WorkerAuthorizationService.State.CLAIMED,claim.state());assertEquals(WorkerAuthorizationService.State.SENT,worker.dispatch(claim.permit(),request->{sends++;return "Fictional response";}).state());
  var contents=new ContentReadService(new JdbcContentReads(c.f.source),tx,ContentReadService.denyUnverifiedReferences());var store=new ResultJdbcStore(c.f.source,claim.permit().identity(),contents,target->false,c.f.clock,"fictional-owner");
  var handler=new AdHocResultHandler(new JdbcFrozenInputs(c.f.source)::read);ResultApplicationService.Handler unused=(i,r,v,m)->{throw new AssertionError("fictional wrong handler");};var core=new ResultApplicationService(c.f.writes,store,Map.of("ad_hoc",handler,"dedup",unused,"value",unused,"analysis",unused,"report",unused));
  var envelope=tools.jackson.databind.json.JsonMapper.shared().createObjectNode().put("schema_version","1").put("input_hash",frozen.inputHash());envelope.set("payload",CanonicalJson.parse("{\"kind\":\"ad_hoc\",\"title\":\"Fictional retained answer\",\"sections\":[],\"citation_ids\":[]}"));
  try{var digest=java.security.MessageDigest.getInstance("SHA-256");digest.update("ai-result-v1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));digest.update(CanonicalJson.bytes(envelope));envelope.put("result_hash",HexFormat.of().formatHex(digest.digest()));}catch(Exception impossible){throw new AssertionError(impossible);}
  assertEquals(ResultApplicationService.Status.APPLIED,core.applyAutomatic(store.input(),JdbcFrozenInputs.canonical(envelope),new SharedWriteExecutor.ActionContext("fictional-read-result",accepted.command().rootId(),frozen.inputHash())).status());
 }
}
