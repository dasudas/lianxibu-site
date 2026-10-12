package com.lianxibu.site.service.jobs;
import com.lianxibu.site.repository.jobs.JdbcAdHocIntentScan;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
/** One durable original question per pass, outside the short preparation writer. */
public final class AdHocPreparationCycle {
    public enum Outcome {IDLE,BUSY,PREPARED,GATED,RECOVERY_REQUIRED}
    private final JdbcAdHocIntentScan scan;private final JdbcOwnerAdHocCreation originals;private final OwnerAdHocPreparationService preparation;private final TransactionTemplate reads;private final AtomicBoolean active=new AtomicBoolean();private String cursor;
    public AdHocPreparationCycle(JdbcAdHocIntentScan scan,JdbcOwnerAdHocCreation originals,OwnerAdHocPreparationService preparation,PlatformTransactionManager tx){
        this.scan=Objects.requireNonNull(scan);this.originals=Objects.requireNonNull(originals);this.preparation=Objects.requireNonNull(preparation);reads=new TransactionTemplate(Objects.requireNonNull(tx));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public Outcome run(BooleanSupplier running){
        Objects.requireNonNull(running);if(!active.compareAndSet(false,true))return Outcome.BUSY;
        try{
            if(TransactionSynchronizationManager.isActualTransactionActive())throw unavailable();if(!running.getAsBoolean())return Outcome.IDLE;
            var page=Objects.requireNonNull(reads.execute(s->scan.page(cursor,1)));
            if(page.intentIds().size()>1||page.nextCursor()!=null&&(page.intentIds().size()!=1||!page.intentIds().getFirst().equals(page.nextCursor())))throw unavailable();
            if(page.intentIds().isEmpty()){cursor=null;return Outcome.IDLE;}
            String intent=page.intentIds().getFirst();if(cursor!=null&&intent.compareTo(cursor)<=0)throw unavailable();if(!running.getAsBoolean())return Outcome.IDLE;
            // Advance scheduling only. Failed original proofs cannot strand later questions.
            cursor=page.nextCursor();var original=Objects.requireNonNull(reads.execute(s->originals.originalIntent(intent)));if(!intent.equals(original.command().intentId()))throw unavailable();
            if(!running.getAsBoolean())return Outcome.IDLE;
            var result=Objects.requireNonNull(preparation.prepare(original.actionKey()));if(!"queued".equals(result.state())||result.priority()!=14)throw unavailable();return Outcome.PREPARED;
        }catch(GenerationFlowService.CapacityGuarded denied){return Outcome.GATED;}catch(RuntimeException damaged){return Outcome.RECOVERY_REQUIRED;}
        finally{active.set(false);}
    }
    private static IllegalStateException unavailable(){return new IllegalStateException("temporary preparation recovery required");}
    @Override public String toString(){return "AdHocPreparationCycle[redacted]";}
}
