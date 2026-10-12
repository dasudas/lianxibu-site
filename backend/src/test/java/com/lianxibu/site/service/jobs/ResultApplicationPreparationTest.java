package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.repository.content.JdbcGeneratedContents;
import com.lianxibu.site.service.content.GeneratedContentService;
import com.lianxibu.site.service.jobs.ResultApplicationService.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Original native claim/request/retention/result writer. External model transport stays fictional. */
class ResultApplicationPreparationTest extends ResultJdbcStoreTest {
    int preparations,preparedExecutions;boolean failPreparation,nullPreparation,retryPreparedOnce;
    @Override ResultApplicationService applicationService(Store applicationStore){
        Handler handler=new Handler(){
            @Override public Handler prepare(Input input,Validated result){assertFalse(TransactionSynchronizationManager.isActualTransactionActive());preparations++;if(failPreparation)throw new IllegalStateException("fictional result preparation failure");if(nullPreparation)return null;return (i,r,c,m)->{assertEquals(input,i);assertEquals(result,r);assertTrue(TransactionSynchronizationManager.isActualTransactionActive());assertFalse(m);preparedExecutions++;var written=write(i,r,c,m);if(retryPreparedOnce&&preparedExecutions==1)throw new CannotAcquireLockException("fictional original result writer retry");return written;};}
            @Override public Applied apply(Input i,Validated r,Current c,boolean manual){if(!manual)throw new AssertionError("automatic result must consume prepared handler");return write(i,r,c,true);}
        };
        Handler unused=(a,b,c,d)->{throw new AssertionError("unassembled branch");};return new ResultApplicationService(writes,applicationStore,Map.of("report",handler,"analysis",unused,"dedup",unused,"value",unused,"ad_hoc",unused));
    }
    Applied write(Input input,Validated result,Current current,boolean manual){handlerCalls++;var ctx=store.context();var body=CanonicalJson.parse(result.payloadJson()).get("body");var generated=new GeneratedContentService(new JdbcGeneratedContents(source),clock);var applied=generated.write(new GeneratedContentService.Write(ctx.contentKind(),ctx.contentId(),manual?current.contentRevision():input.expectedContentRevision(),input.rootId(),JdbcFrozenInputs.canonical(body)));if(applied==null)return null;new JdbcJobRecords(source).ensureIntent(new JdbcJobRecords.Intent("fictional-downstream","fictional-downstream-key",1,"daily_report",LocalDate.of(2026,1,1)),clock.instant());if(failDownstream)throw new IllegalStateException("fictional downstream failure");return new Applied(applied.id(),applied.revision());}
    @Test void ActualOriginalResultPreparesOnceOutsideWriterAndAppliesExactImmutableResult(){var result=apply();assertEquals(Status.APPLIED,result.status());assertEquals(1,preparations);assertEquals(1,preparedExecutions);assertEquals(1,count("contents"));assertEquals(1,sends);assertEquals(1,new JdbcJobRecords(source).byId(ROOT).callCount());}
    @Test void DurableOriginalActionReceiptWinsBeforeFailingPreparationOnReplay(){var result=apply();failPreparation=true;jdbc.update("UPDATE article_revisions SET effective_valid=false");assertEquals(result,apply());assertEquals(1,preparations);assertEquals(1,preparedExecutions);assertEquals(1,count("contents"));assertEquals(1,sends);}
    @Test void FailedOrNullPreparationDoesNotRetainWriteReleaseSlotOrSpendAgain(){failPreparation=true;assertThrows(RuntimeException.class,this::apply);assertEquals(0,count("contents"));assertNull(new JdbcResultState(source).root(ROOT).resultHash());assertEquals("occupied",new JdbcWorkerState(source).slot().state());failPreparation=false;nullPreparation=true;assertThrows(RuntimeException.class,this::apply);assertEquals(0,count("contents"));assertNull(new JdbcResultState(source).root(ROOT).resultHash());assertEquals(1,new JdbcJobRecords(source).byId(ROOT).callCount());assertEquals(1,sends);nullPreparation=false;assertEquals(Status.APPLIED,apply().status());assertEquals(3,preparations);assertEquals(1,preparedExecutions);}
    @Test void NativeWriterRetryReusesPurePreparedHandlerAndRollsBackEveryFirstAttemptWrite(){retryPreparedOnce=true;assertEquals(Status.APPLIED,apply().status());assertEquals(1,preparations);assertEquals(2,preparedExecutions);assertEquals(1,count("contents"));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE id='fictional-downstream'",Integer.class));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE target_type='result_application'",Integer.class));assertEquals(1,sends);}
    @Test void AckUnknownReadsOriginalReceiptWithoutPreparingOrApplyingAgain(){tx.ackLost=true;var result=apply();assertEquals(Status.APPLIED,result.status());assertEquals(1,preparations);assertEquals(1,preparedExecutions);assertEquals(result,apply());assertEquals(1,preparations);assertEquals(1,count("contents"));assertEquals(1,sends);}
}
