package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class SourceRequestDispatchTest extends SourceStageBirthConfigurationTest {
    @Override DispatchReadinessService dispatch(){return legacyDispatch();}
    GenerationFlowService.Frozen prepared(){archive(1,0);return prepare(archive(2,5));}
    boolean ready(String root){return f.tx(()->dispatch().ready(root));}
    @Test void actualRequestCannotBeReplacedWithFullRawBodyWhileInputHashStaysOriginal(){
        var original=prepared();assertTrue(ready(original.ownerId()));var request=(ObjectNode)CanonicalJson.parse(original.requestJson());((ObjectNode)request.get("messages").get(1)).put("content",original.snapshotJson());
        f.f.jdbc.update("UPDATE jobs SET request_json=? WHERE record_kind='ai_attempt'",JdbcFrozenInputs.canonical(request));int count=f.f.count("1=1");assertThrows(RuntimeException.class,()->ready(original.ownerId()));assertEquals(count,f.f.count("1=1"));assertEquals(0,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void legitimateRetryAndRunningRequestStillBindTheExactOriginalChunk(){
        var original=prepared();f.f.jdbc.update("UPDATE jobs SET state='failed' WHERE record_kind='ai_attempt'");f.f.jdbc.update("UPDATE jobs SET call_count=1,state='waiting_retry' WHERE id=?",original.ownerId());
        f.tx(()->new JdbcJobRecords(f.f.source).prepareAttempt(new JdbcJobRecords.Attempt("fictional-retry-attempt",original.ownerId(),2,original.requestJson(),original.ownerId(),original.inputHash()),f.f.clock.instant()));assertTrue(ready(original.ownerId()));
        f.f.jdbc.update("UPDATE jobs SET call_count=2,state='running' WHERE id=?",original.ownerId());f.f.jdbc.update("UPDATE jobs SET state='running' WHERE id='fictional-retry-attempt'");assertTrue(ready(original.ownerId()));
        var wrong=(ObjectNode)CanonicalJson.parse(original.requestJson());wrong.put("prompt_version","fictional-altered-prompt");f.f.jdbc.update("UPDATE jobs SET request_json=? WHERE id='fictional-retry-attempt'",JdbcFrozenInputs.canonical(wrong));assertThrows(RuntimeException.class,()->ready(original.ownerId()));assertEquals(2,f.f.jdbc.queryForObject("SELECT call_count FROM jobs WHERE id=?",Integer.class,original.ownerId()));
    }
    @Test void missingRequestOrDifferentInputOwnerHashAndAttemptStateReject(){
        var original=prepared();String attempt=f.f.jdbc.queryForObject("SELECT id FROM jobs WHERE record_kind='ai_attempt'",String.class);
        f.f.jdbc.update("UPDATE jobs SET input_hash=? WHERE id=?",new byte[32],attempt);assertThrows(RuntimeException.class,()->ready(original.ownerId()));f.f.jdbc.update("UPDATE jobs SET input_hash=? WHERE id=?",java.util.HexFormat.of().parseHex(original.inputHash()),attempt);
        f.f.jdbc.update("UPDATE jobs SET snapshot_owner_id=? WHERE id=?",f.f.jdbc.queryForObject("SELECT intent_id FROM jobs WHERE id=?",String.class,original.ownerId()),attempt);assertThrows(RuntimeException.class,()->ready(original.ownerId()));f.f.jdbc.update("UPDATE jobs SET snapshot_owner_id=? WHERE id=?",original.ownerId(),attempt);
        f.f.jdbc.update("UPDATE jobs SET state='failed' WHERE id=?",attempt);assertThrows(RuntimeException.class,()->ready(original.ownerId()));f.f.jdbc.update("DELETE FROM jobs WHERE id=?",attempt);assertThrows(RuntimeException.class,()->ready(original.ownerId()));
    }
    @Test void oversizedOrNoncontiguousAttemptAndWrongCallStateNeverDispatch(){
        var original=prepared();String attempt=f.f.jdbc.queryForObject("SELECT id FROM jobs WHERE record_kind='ai_attempt'",String.class);var oversized=(ObjectNode)CanonicalJson.parse(original.requestJson());((ObjectNode)oversized.get("messages").get(1)).put("content","Fictional oversized "+"x".repeat(2*1048576));
        f.f.jdbc.update("UPDATE jobs SET request_json=? WHERE id=?",JdbcFrozenInputs.canonical(oversized),attempt);assertThrows(RuntimeException.class,()->ready(original.ownerId()));f.f.jdbc.update("UPDATE jobs SET request_json=?,attempt_no=2 WHERE id=?",original.requestJson(),attempt);assertThrows(RuntimeException.class,()->ready(original.ownerId()));
        f.f.jdbc.update("UPDATE jobs SET attempt_no=1,state='running' WHERE id=?",attempt);assertThrows(RuntimeException.class,()->ready(original.ownerId()));f.f.jdbc.update("UPDATE jobs SET state='running',call_count=0 WHERE id=?",original.ownerId());assertThrows(RuntimeException.class,()->ready(original.ownerId()));
    }
}
