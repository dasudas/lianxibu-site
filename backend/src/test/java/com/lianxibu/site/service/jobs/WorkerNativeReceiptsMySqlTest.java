package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.WorkerAuthorizationService.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Real original day claim/send receipts. Fixture model genesis and Sender remain fictional. */
@EnabledIfEnvironmentVariable(named="B45_MYSQL",matches="true")
class WorkerNativeReceiptsMySqlTest {
    SourceDayWorkerIntegrationMySqlTest f;Claim claim;JdbcWorkerState state;
    @BeforeEach void setup()throws Exception{f=new SourceDayWorkerIntegrationMySqlTest();f.setup();claim=f.worker.claim(f.action());assertEquals(State.CLAIMED,claim.state());state=f.state;}
    String key(){return JdbcResultState.sha(f.action().actionKey());}
    String targetHash(){return f.db.jdbc.queryForObject("SELECT LOWER(HEX(target_key_hash)) FROM jobs WHERE target_type='worker_claim'",String.class);}
    @Test void actualOriginalClaimAndSendHashesAcceptNativeMysqlJsonReordering(){assertNotNull(state.receipt(key(),"worker_claim"));assertNotNull(state.claimReceipt(targetHash()));assertNotNull(new JdbcWorkerRecovery(f.db.source,state).attempt(claim.permit().attemptId()));assertEquals(State.SENT,f.worker.dispatch(claim.permit(),json->"fictional-receipt-result").state());assertEquals(1,f.db.count("target_type='worker_send'"));assertNotNull(state.claimReceipt(targetHash()));}
    @Test void damagedOriginalPayloadHashRefusesReplayAndRecoveryWithoutMutatingSlot(){var before=state.slot();f.db.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='worker_claim'",new byte[32]);assertThrows(RuntimeException.class,()->state.receipt(key(),"worker_claim"));assertThrows(RuntimeException.class,()->state.claimReceipt(targetHash()));assertThrows(RuntimeException.class,()->new JdbcWorkerRecovery(f.db.source,state).attempt(claim.permit().attemptId()));assertThrows(RuntimeException.class,()->f.worker.claim(f.action()));assertEquals(before,state.slot());assertEquals(1,new JdbcJobRecords(f.db.source).byId(f.root).callCount());assertEquals(0,f.db.count("target_type='worker_send'"));}
    @Test void targetIdentityHashRoleAndOwnerDamageAreNotAnOriginalProof()throws Exception{f.db.jdbc.update("UPDATE jobs SET target_key_hash=? WHERE target_type='worker_claim'",new byte[32]);assertThrows(RuntimeException.class,()->state.receipt(key(),"worker_claim"));setup();f.db.jdbc.update("UPDATE jobs SET operator_id='fictional-other-epoch' WHERE target_type='worker_claim'");assertThrows(RuntimeException.class,()->state.receipt(key(),"worker_claim"));assertThrows(RuntimeException.class,()->new JdbcWorkerRecovery(f.db.source,state).attempt(claim.permit().attemptId()));setup();f.db.jdbc.update("UPDATE jobs SET record_kind='generation_intent' WHERE target_type='worker_claim'");assertThrows(RuntimeException.class,()->state.receipt(key(),"worker_claim"));}
    @Test void oversizedOriginalReceiptIsRefusedBeforeRawPayloadRead(){f.db.jdbc.update("UPDATE jobs SET action_payload=? WHERE target_type='worker_claim'","{\"fictional\":\""+"F".repeat(3300000)+"\"}");assertThrows(RuntimeException.class,()->state.receipt(key(),"worker_claim"));assertThrows(RuntimeException.class,()->state.claimReceipt(targetHash()));assertEquals("occupied",state.slot().state());}
}
