package com.lianxibu.site.service.jobs;

import com.lianxibu.site.repository.jobs.JdbcSourceIntentScan;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** One original parent per pass. Cursor is scheduling state; durable proofs own all progress. */
public final class SourceAnalysisPreparationCycle {
    public enum Outcome { IDLE,BUSY,PREPARED,HANDED_OFF,FINALIZED,PENDING,GATED,RECOVERY_REQUIRED }
    private final JdbcSourceIntentScan scan;
    private final SourceAnalysisParentStepService parent;
    private final TransactionTemplate reads;
    private final AtomicBoolean active=new AtomicBoolean();
    private String cursor;
    public SourceAnalysisPreparationCycle(JdbcSourceIntentScan scan,SourceAnalysisParentStepService parent,PlatformTransactionManager tx){
        this.scan=Objects.requireNonNull(scan);this.parent=Objects.requireNonNull(parent);
        reads=new TransactionTemplate(Objects.requireNonNull(tx));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public Outcome run(BooleanSupplier running){
        Objects.requireNonNull(running);if(!active.compareAndSet(false,true))return Outcome.BUSY;
        try{
            if(TransactionSynchronizationManager.isActualTransactionActive())throw unavailable();
            if(!running.getAsBoolean())return Outcome.IDLE;
            var page=Objects.requireNonNull(reads.execute(s->scan.page(cursor,1)));
            if(page.candidates().size()>1||page.hasMore()&&(page.candidates().size()!=1||!page.candidates().getFirst().id().equals(page.nextCursor()))||!page.hasMore()&&page.nextCursor()!=null)throw unavailable();
            if(page.candidates().isEmpty()){cursor=null;return Outcome.IDLE;}
            var candidate=page.candidates().getFirst();
            if(candidate.id()==null||!candidate.id().matches("[a-z0-9._:-]{1,64}")||cursor!=null&&candidate.id().compareTo(cursor)<=0)throw unavailable();
            if(!running.getAsBoolean())return Outcome.IDLE;
            // A failed parent cannot strand subsequent parents. Native scan failures leave
            // this cursor unchanged because unknown rows cannot be silently skipped.
            cursor=page.nextCursor();
            var result=Objects.requireNonNull(parent.step(candidate));
            if(!candidate.id().equals(result.intentId()))throw unavailable();
            return Outcome.valueOf(result.status().name());
        }catch(RuntimeException rejected){return Outcome.RECOVERY_REQUIRED;}
        finally{active.set(false);}
    }
    private static IllegalStateException unavailable(){return new IllegalStateException("original source parent cycle unavailable");}
    @Override public String toString(){return "SourceAnalysisPreparationCycle[redacted]";}
}
