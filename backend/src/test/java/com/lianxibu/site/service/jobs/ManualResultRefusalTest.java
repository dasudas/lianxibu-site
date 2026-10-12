package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.ResultApplicationService.*;
import org.junit.jupiter.api.Test;

class ManualResultRefusalTest extends ManualResultProofTest {
    @Test void manualBusinessConditionsRemainDistinctAndDoNotWrite(){
        long current=revision();
        assertEquals(ManualRefusal.REVISION_CONFLICT,assertThrows(ManualRejected.class,()->apply("fictional-root-cas",current-1,1L)).refusal());
        assertEquals(ManualRefusal.REVISION_CONFLICT,assertThrows(ManualRejected.class,()->apply("fictional-body-cas",current,0L)).refusal());
        assertEquals(ManualRefusal.IDENTITY_CONFLICT,assertThrows(ManualRejected.class,()->application.applyManual(strict.input(),"0".repeat(64),current,1L,"Fictional wrong hash",true,f.action("fictional-confirmed-hash"))).refusal());
        f.jdbc.update("UPDATE article_revisions SET effective_valid=false");assertEquals(ManualRefusal.INVALID_EVIDENCE,assertThrows(ManualRejected.class,()->apply("fictional-ref-cas",current,1L)).refusal());f.jdbc.update("UPDATE article_revisions SET effective_valid=true");
        f.writes.execute(f.action("fictional-refusal-generation"),k->null,c->{new JdbcJobRecords(f.source).ensureIntent(new JdbcJobRecords.Intent("fictional-refusal-intent",f.generation.command(false).logicalKey(),2,"daily_report",java.time.LocalDate.of(2026,1,1)),f.clock.instant());return true;});
        assertEquals(ManualRefusal.SUPERSEDED,assertThrows(ManualRejected.class,()->apply("fictional-generation-cas",current,1L)).refusal());assertEquals(current,revision());assertEquals(0,f.handlerCalls);
    }
    @Test void committedKeyRebindingIsBusinessConflictButNativeReceiptDamageIsStorageFailure(){long current=revision();var original=apply("fictional-rebound-manual",current,1L);
        assertEquals(ManualRefusal.IDENTITY_CONFLICT,assertThrows(ManualRejected.class,()->application.applyManual(strict.input(),hash(),current,1L,"Fictional changed reason",true,f.action("fictional-rebound-manual"))).refusal());
        var content=new com.lianxibu.site.service.content.ContentReadService(new com.lianxibu.site.repository.content.JdbcContentReads(f.source),f.tx,com.lianxibu.site.service.content.ContentReadService.denyUnverifiedReferences());
        var other=new ResultJdbcStore(f.source,f.identity,content,target->true,f.clock,"fictional-other-owner",true);
        assertEquals(ManualRefusal.IDENTITY_CONFLICT,assertThrows(ManualRejected.class,()->f.applicationService(other).applyManual(other.input(),hash(),current,1L,"Fictional verified manual application",true,f.action("fictional-rebound-manual"))).refusal());
        assertEquals(original,apply("fictional-rebound-manual",current,1L));
        f.jdbc.update("UPDATE jobs SET action_payload_hash=? WHERE target_type='result_application' AND operator_id='fictional-owner'",new byte[32]);
        var failure=assertThrows(RuntimeException.class,()->apply("fictional-rebound-manual",current,1L));assertFalse(failure instanceof ManualRejected);assertEquals(1,f.handlerCalls);
    }
    @Test void appliedNewKeyIsRejectedButOriginalReceiptStillReplays(){long current=revision();var result=apply("fictional-refusal-original",current,1L);
        assertEquals(ManualRefusal.RUN_FROZEN,assertThrows(ManualRejected.class,()->apply("fictional-refusal-new",revision(),2L)).refusal());assertEquals(result,apply("fictional-refusal-original",current,1L));assertEquals(1,f.handlerCalls);assertEquals(1,f.sends);
    }
}
