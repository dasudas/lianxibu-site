package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Exact 201 originals, pure work outside and all original writer proofs inside. */
class SourcePartCapturedPreparationTest {
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    SourcePartialStagePreparationTest fixture()throws Exception{var f=new SourcePartialStagePreparationTest(){@Override GenerationJdbcStoreTest database(){return SourcePartCapturedPreparationTest.this.database();}};f.setup(201,true);return f;}
    @Test void Exact201PartsAreCapturedOutsideAndWrittenOneAtATimeWithOriginalReceiptReplay()throws Exception{
        var f=fixture();int calls=f.f.db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class);
        for(int n=0;n<f.plan.chunks().size();n++){
            var captured=f.service.capturePartPartial(f.f.intent,n,f.action(n),f.f.permit);assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            int part=n;var expected=f.plan.chunks().get(n);var frozen=f.f.db.writes.execute(new SharedWriteExecutor.ActionContext(f.action(n),expected.rootId(),expected.inputHash()),key->f.service.committedCaptured(captured),context->f.service.prepareCaptured(captured));
            assertEquals(expected.snapshotJson(),frozen.snapshotJson());assertEquals(expected.requestJson(),frozen.requestJson());assertEquals(frozen,f.f.f.f.f.read(()->f.service.committedPart(f.f.intent,part,f.action(part))));
        }
        assertTrue(f.progress().complete());assertEquals(calls,f.f.db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
    @Test void ChangedNativeAuthorityAndForeignServiceCannotUsePureCapture()throws Exception{
        var f=fixture();var capture=f.service.capturePartPartial(f.f.intent,0,f.action(0),f.f.permit);int count=f.f.db.count("1=1");
        assertThrows(RuntimeException.class,()->f.service.prepareCaptured(capture));assertThrows(RuntimeException.class,()->f.f.f.f.f.p1.tx(()->{f.service.capturePartPartial(f.f.intent,0,f.action(0),f.f.permit);return true;}));
        var other=f.provider(new SourceDedupDependencyService(f.f.f.observations,f.f.f.proven,f.f.f.service));assertThrows(RuntimeException.class,()->f.f.f.f.f.p1.tx(()->other.prepareCaptured(capture)));
        String root=f.f.f.f.plan.chunks().getLast().rootId();f.f.db.jdbc.update("UPDATE jobs SET revision=revision+1 WHERE id=?",root);assertThrows(RuntimeException.class,()->f.f.f.f.f.p1.tx(()->f.service.prepareCaptured(capture)));assertEquals(count,f.f.db.count("1=1"));
    }
}
