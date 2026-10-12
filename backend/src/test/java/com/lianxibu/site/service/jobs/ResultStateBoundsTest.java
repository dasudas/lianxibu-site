package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.jobs.*;
import org.junit.jupiter.api.*;

class ResultStateBoundsTest {
    ResultJdbcStoreTest f;
    ResultJdbcStoreTest fixture(){return new ResultJdbcStoreTest();}
    @BeforeEach void setup(){f=fixture();f.fixture();}
    JdbcResultState state(){return new JdbcResultState(f.source);}
    JdbcResultState.Identity identity(){var id=f.identity;return new JdbcResultState.Identity(id.rootId(),id.attemptId(),id.dispatchToken(),id.slotRevision(),id.fencing(),id.leaseUntil(),id.frozenRequestJson(),id.inputHash());}
    @Test void claimRejectsOversizedRequestAndUnexpectedAttemptPayloadWithoutAnyMutation(){
        var slot=new JdbcWorkerState(f.source).slot();f.jdbc.update("UPDATE jobs SET request_json=? WHERE id=?","{\"fictional\":\""+"X".repeat(1048577)+"\"}",f.identity.attemptId());assertThrows(RuntimeException.class,()->state().claim(identity()));assertEquals(slot,new JdbcWorkerState(f.source).slot());
        setup();f.jdbc.update("UPDATE jobs SET payload_json='{}' WHERE id=?",f.identity.attemptId());assertThrows(RuntimeException.class,()->state().claim(identity()));assertEquals(1,f.sends);
    }
    @Test void rootManifestAndResultPartCannotReturnOversizedData(){
        f.existingContent();f.apply();f.jdbc.update("UPDATE jobs SET result_json=? WHERE id=?","{\"fictional\":\""+"X".repeat(16385)+"\"}",ResultJdbcStoreTest.ROOT);
        assertThrows(RuntimeException.class,()->state().root(ResultJdbcStoreTest.ROOT));
        setup();f.existingContent();f.apply();String oversized="{\"fictional\":\""+"X".repeat(262145)+"\"}";f.jdbc.update("UPDATE jobs SET payload_json=?,payload_hash=? WHERE purpose='result'",oversized,java.util.HexFormat.of().parseHex(com.lianxibu.site.domain.protocol.CanonicalJson.hash(com.lianxibu.site.domain.protocol.CanonicalJson.Domain.INPUT_PART,com.lianxibu.site.domain.protocol.CanonicalJson.parse(oversized))));assertThrows(RuntimeException.class,()->state().retained(state().root(ResultJdbcStoreTest.ROOT)));
    }
    @Test void receiptPayloadAndTargetBoundsRefuseBeforeReturn(){
        f.apply();String payload="{\"fictional\":\""+"X".repeat(16385)+"\"}";f.jdbc.update("UPDATE jobs SET action_payload=?,action_payload_hash=? WHERE target_type='result_application'",payload,java.util.HexFormat.of().parseHex(JdbcResultState.sha(payload)));assertThrows(RuntimeException.class,()->state().receipt("fictional-apply","fictional-owner",ResultJdbcStoreTest.ROOT,f.identity.attemptId()));
        setup();f.apply();String target="{\"root_id\":\"fictional-root\",\"attempt_id\":\""+f.identity.attemptId()+"\",\"padding\":\""+"X".repeat(16385)+"\"}";f.jdbc.update("UPDATE jobs SET target_key_json=?,target_key_hash=? WHERE target_type='result_application'",target,java.util.HexFormat.of().parseHex(JdbcResultState.sha(target)));assertThrows(RuntimeException.class,()->state().receipt("fictional-apply","fictional-owner",ResultJdbcStoreTest.ROOT,f.identity.attemptId()));
    }
    @Test void originalAttemptInputOwnerHashAndSendAuthorizationStillBindTheClaim(){
        f.jdbc.update("UPDATE jobs SET snapshot_owner_id='fictional-other' WHERE id=?",f.identity.attemptId());assertThrows(RuntimeException.class,()->state().claim(identity()));
        setup();f.jdbc.update("UPDATE jobs SET input_hash=? WHERE id=?",new byte[32],f.identity.attemptId());assertThrows(RuntimeException.class,()->state().claim(identity()));
        setup();f.jdbc.update("UPDATE jobs SET send_authorized_at=NULL WHERE id=?",f.identity.attemptId());assertThrows(RuntimeException.class,()->state().claim(identity()));
    }
}
