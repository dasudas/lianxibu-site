package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
/** Actual native worker receipts at exact-second, millisecond and microsecond boundaries. */
@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class WorkerMicrosecondReceiptMySqlTest {
 GenerationJdbcStoreMySqlTest f;WorkerAuthorizationService worker;JdbcWorkerState state;
 void setup(String instant){f=new GenerationJdbcStoreMySqlTest();f.fixture();f.clock=Clock.fixed(Instant.parse(instant),ZoneOffset.UTC);
  f.jdbc.execute("ALTER TABLE worker_lock ADD(slot_state VARCHAR(16) DEFAULT 'free',slot_revision BIGINT DEFAULT 0,fencing_token BIGINT DEFAULT 0,job_id VARCHAR(64),holder VARCHAR(64),lease_until DATETIME(6),request_deadline DATETIME(6),occupied_since DATETIME(6))");
  for(String c:List.of("worker_instance_id VARCHAR(64)","dispatch_token BIGINT","request_deadline DATETIME(6)","send_authorized_at DATETIME(6)","slot_released_at DATETIME(6)","release_reason VARCHAR(2048)"))f.jdbc.execute("ALTER TABLE jobs ADD "+c);
  f.store=new GenerationJdbcStore(f.source,f.clock,"fictional-owner",GenerationJdbcStoreTest.OWNER,f.command(false));new GenerationFlowService(f.writes,f.store,(stage,snapshot,hash)->"{\"provider\":\"deepseek\",\"model\":\"deepseek-flash\",\"input_hash\":\""+hash+"\",\"messages\":[{\"role\":\"user\",\"content\":\"Fictional original request\"}],\"response_format\":{\"type\":\"json_object\"},\"parameters\":{},\"schema_version\":\"1\",\"prompt_version\":\"1\"}",f.clock,()->true).prepare(f.command(false),f.action());
  state=new JdbcWorkerState(f.source);worker=new WorkerAuthorizationService(f.writes,new WorkerJdbcStore(state,new JdbcJobRecords(f.source),"fictional-micro-worker",()->false,id->true,f.clock),f.clock,()->true);
 }
 SharedWriteExecutor.ActionContext action(){return new SharedWriteExecutor.ActionContext("fictional-micro-claim",GenerationJdbcStoreTest.OWNER,null);}
 void check(String type,String field,Instant expected){var text=f.jdbc.queryForObject("SELECT action_payload FROM jobs WHERE target_type=?",String.class,type);var value=CanonicalJson.parse(text).get(field);assertEquals(expected,WireValues.utcMicroseconds(value));assertTrue(value.stringValue().matches(".*\\.[0-9]{6}Z"));}
 @Test void ExactSecondClaimAndSendCanBeReadByTheExistingStrictMicrosecondContract(){setup("2026-01-01T21:00:00Z");var claimed=worker.claim(action());assertEquals(WorkerAuthorizationService.State.CLAIMED,claimed.state());check("worker_claim","started_at",f.clock.instant());check("worker_claim","lease_until",claimed.permit().identity().leaseUntil());assertEquals(WorkerAuthorizationService.State.SENT,worker.dispatch(claimed.permit(),r->"Fictional result").state());check("worker_send","send_authorized_at",f.clock.instant());check("worker_send","lease_until",claimed.permit().identity().leaseUntil());assertEquals(claimed.permit().identity(),worker.claim(action()).permit().identity());assertEquals(1,new JdbcJobRecords(f.source).byId(GenerationJdbcStoreTest.OWNER).callCount());}
 @Test void MillisecondAndMicrosecondFractionsArePaddedWithoutChangingTheirNativeInstant(){for(String time:List.of("2026-01-01T21:00:00.123Z","2026-01-01T21:00:00.123456Z")){setup(time);var claimed=worker.claim(action());check("worker_claim","started_at",f.clock.instant());check("worker_claim","lease_until",claimed.permit().identity().leaseUntil());assertEquals(WorkerAuthorizationService.State.SENT,worker.dispatch(claimed.permit(),r->"Fictional result").state());check("worker_send","send_authorized_at",f.clock.instant());}}
 @Test void ProvenNotSentRefundKeepsStrictNextEligibleTimeAndDoesNotAuthorizeAnotherSend(){setup("2026-01-01T21:00:00Z");var claimed=worker.claim(action());var id=claimed.permit().identity();Instant eligible=f.clock.instant().plusSeconds(60);var store=new WorkerJdbcStore(state,new JdbcJobRecords(f.source),"fictional-micro-worker",()->false,r->true,f.clock);f.writes.execute(new SharedWriteExecutor.ActionContext("fictional-micro-refund",id.rootId(),id.inputHash()),k->null,c->store.refundNotSent(id,eligible,"Fictional proven not sent",c.actionKey()));check("worker_not_sent","next_eligible_at",eligible);check("worker_not_sent","lease_until",id.leaseUntil());assertEquals(0,new JdbcJobRecords(f.source).byId(id.rootId()).callCount());assertEquals("free",state.slot().state());assertEquals(0,f.count("target_type='worker_send'"));}
}
