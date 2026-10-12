package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import org.junit.jupiter.api.*;

class AttemptUsageServiceTest {
    ResultJdbcStoreTest f;
    AttemptUsageService service;
    @BeforeEach void setup(){f=new ResultJdbcStoreTest();f.fixture();service=new AttemptUsageService(f.writes,new JdbcAttemptUsage(f.source),f.clock,"fictional-epoch");}
    SharedWriteExecutor.ActionContext action(String key){return new SharedWriteExecutor.ActionContext(key,"fictional-usage",null);}
    AttemptUsageService.Metadata known(){return new AttemptUsageService.Metadata("fictional-response",10L,2L,12L,3L);}
    String usage(){return f.jdbc.queryForObject("SELECT usage_json FROM jobs WHERE id=?",String.class,f.identity.attemptId());}
    @Test void exactUsageSurvivesAckLossAndRestartWithoutChangingRootBudgetOrSlot() {
        var slot=new JdbcWorkerState(f.source).slot();long rootRevision=new JdbcJobRecords(f.source).byId(f.identity.rootId()).revision();
        f.tx.ackLost=true;assertTrue(service.record(f.identity,known(),action("usage-one")));
        var value=CanonicalJson.parse(usage());assertEquals(10,value.path("prompt_tokens").asLong());assertEquals(2,value.path("completion_tokens").asLong());
        assertEquals("fictional-response",f.jdbc.queryForObject("SELECT provider_request_id FROM jobs WHERE id=?",String.class,f.identity.attemptId()));
        assertNull(f.jdbc.queryForObject("SELECT cost_decimal FROM jobs WHERE id=?",String.class,f.identity.attemptId()));
        assertEquals(slot,new JdbcWorkerState(f.source).slot());assertEquals(rootRevision,new JdbcJobRecords(f.source).byId(f.identity.rootId()).revision());
        f.apply();assertTrue(new AttemptUsageService(f.writes,new JdbcAttemptUsage(f.source),f.clock,"fictional-epoch").record(f.identity,known(),action("usage-one")));
        assertThrows(RuntimeException.class,()->service.record(f.identity,known(),action("new-after-complete")));
        assertEquals(1,new JdbcJobRecords(f.source).byId(f.identity.rootId()).callCount());
    }
    @Test void missingUsageStaysUnknownAndChangedMetadataOrReceiptCannotReplay() {
        var unknown=new AttemptUsageService.Metadata(null,null,null,null,null);service.record(f.identity,unknown,action("unknown"));
        var value=CanonicalJson.parse(usage());assertEquals("unknown",value.path("state").asText());assertTrue(value.path("prompt_tokens").isNull());assertTrue(value.path("completion_tokens").isNull());
        assertThrows(RuntimeException.class,()->service.record(f.identity,known(),action("unknown")));
        assertThrows(RuntimeException.class,()->service.record(f.identity,known(),action("different")));
        f.jdbc.update("UPDATE jobs SET action_payload=? WHERE target_type='attempt_usage'","{\"schema_version\":\"fictional-tamper\"}");
        assertThrows(RuntimeException.class,()->service.record(f.identity,unknown,action("unknown")));
    }
    @Test void malformedCountsAndFailedReceiptNeverLeavePartialMetadata() {
        for(var metadata:java.util.List.of(new AttemptUsageService.Metadata("fictional",-1L,1L,0L,null),new AttemptUsageService.Metadata("fictional",1L,2L,4L,null),new AttemptUsageService.Metadata("fictional",1L,2L,3L,2L),new AttemptUsageService.Metadata("bad\nresponse",1L,2L,3L,null)))
            assertThrows(IllegalArgumentException.class,()->service.record(f.identity,metadata,action("bad")));
        f.jdbc.execute("ALTER TABLE jobs ADD CONSTRAINT fictional_usage_fail CHECK(target_type IS NULL OR target_type<>'attempt_usage')");
        assertThrows(RuntimeException.class,()->service.record(f.identity,known(),action("receipt-fails")));
        assertNull(usage());assertNull(f.jdbc.queryForObject("SELECT provider_request_id FROM jobs WHERE id=?",String.class,f.identity.attemptId()));
    }
}
