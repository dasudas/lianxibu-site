package com.lianxibu.site.service.jobs;
import com.lianxibu.site.repository.jobs.JdbcCatalogAnalysisDays;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;

/** One original day and at most one entity preparation per tick. Native receipts own progress. */
public final class CatalogDayAnalysisPreparationCycle {
    public enum Outcome { IDLE,BUSY,PENDING,SETTLED,PREPARED,GATED,RECOVERY_REQUIRED }
    private final JdbcCatalogAnalysisDays days;
    private final CatalogDayEntityEvidenceService evidence;
    private final CatalogDayAnalysisEligibilityService eligibility;
    private final CatalogDayAnalysisReservationLedger reservations;
    private final CatalogDayAnalysisGenerationService allocation;
    private final CatalogAnalysisPreparedInputService originals;
    private final CatalogDayAnalysisPreparationService preparation;
    private final TransactionTemplate reads;
    private final Clock clock;
    private final BooleanSupplier allowed;
    private final String category;
    private final AtomicBoolean active=new AtomicBoolean();
    private LocalDate cursor;
    private JdbcCatalogAnalysisDays.Day pending;
    private LocalDate following;
    private String entityCursor;
    public CatalogDayAnalysisPreparationCycle(JdbcCatalogAnalysisDays days,CatalogDayEntityEvidenceService evidence,
            CatalogDayAnalysisEligibilityService eligibility,CatalogDayAnalysisReservationLedger reservations,
            CatalogDayAnalysisGenerationService allocation,CatalogAnalysisPreparedInputService originals,
            CatalogDayAnalysisPreparationService preparation,PlatformTransactionManager tx,Clock clock,BooleanSupplier allowed,String category){
        this.days=Objects.requireNonNull(days);this.evidence=Objects.requireNonNull(evidence);this.eligibility=Objects.requireNonNull(eligibility);
        this.reservations=Objects.requireNonNull(reservations);this.allocation=Objects.requireNonNull(allocation);this.originals=Objects.requireNonNull(originals);
        this.preparation=Objects.requireNonNull(preparation);this.clock=Objects.requireNonNull(clock);this.allowed=Objects.requireNonNull(allowed);
        this.category=switch(Objects.requireNonNull(category)){case "agent","skill","workflow","model"->category;default->throw unavailable();};
        reads=new TransactionTemplate(Objects.requireNonNull(tx));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public Outcome run(BooleanSupplier running){
        Objects.requireNonNull(running);if(!active.compareAndSet(false,true))return Outcome.BUSY;
        boolean entityReached=false;
        try{
            if(TransactionSynchronizationManager.isActualTransactionActive())throw unavailable();if(!running.getAsBoolean()||Thread.currentThread().isInterrupted())return Outcome.IDLE;
            var through=Objects.requireNonNull(clock.instant()).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().minusDays(1);
            if(pending==null){
                if(cursor!=null&&!cursor.isBefore(through))cursor=null;
                var page=Objects.requireNonNull(reads.execute(s->days.page(cursor,through,1)));
                if(page.days().size()>1||page.hasMore()&&(page.days().size()!=1||!page.days().getFirst().day().equals(page.nextCursor()))||!page.hasMore()&&page.nextCursor()!=null)throw unavailable();
                if(page.days().isEmpty()){cursor=null;return Outcome.IDLE;}
                var locator=page.days().getFirst();if(cursor!=null&&!locator.day().isAfter(cursor)||locator.day().isAfter(through))throw unavailable();
                if(!running.getAsBoolean())return Outcome.IDLE;pending=locator;following=page.nextCursor();entityCursor=null;
            }
            var locator=pending;if(locator.day().isAfter(through))throw unavailable();
            var original=evidence.capture(locator.day());if(original==null){advanceDay();return Outcome.PENDING;}
            if(!category.equals(original.original().category())||!locator.ownerId().equals(original.original().closed().original().plan().directory().ownerId()))throw unavailable();
            CatalogDayAnalysisEligibilityService.Captured selected=null;
            for(var entity:original.entities().stream().sorted(Comparator.comparing(CatalogDayEntityEvidenceService.Entity::entityId)).toList()){
                if(entityCursor!=null&&entity.entityId().compareTo(entityCursor)<=0)continue;
                if(!running.getAsBoolean()||Thread.currentThread().isInterrupted())return Outcome.IDLE;
                String id=entity.entityId();entityCursor=id;entityReached=true;var prepared=originals.find(locator.day(),id);
                if(prepared!=null)continue;
                var reservation=reads.execute(s->reservations.read(locator.day(),id));
                if(reservation==null){
                    if(selected==null)selected=Objects.requireNonNull(eligibility.captureOriginal(locator.day()));
                    if(selected.selected().stream().noneMatch(value->id.equals(value.entity().entityId())))continue;
                }
                if(!allowed.getAsBoolean()){advanceDay();return Outcome.GATED;}if(!running.getAsBoolean())return Outcome.IDLE;
                if(reservation==null)allocation.reserve(locator.day(),id);
                if(!running.getAsBoolean())return Outcome.IDLE;
                var input=Objects.requireNonNull(preparation.prepare(locator.day(),id));
                if(!category.equals(input.reservation().category())||!locator.day().equals(input.reservation().day())||!id.equals(input.reservation().entityId()))throw unavailable();
                return Outcome.PREPARED;
            }
            advanceDay();return Outcome.SETTLED;
        }catch(RuntimeException rejected){if(!entityReached)advanceDay();return Outcome.RECOVERY_REQUIRED;}
        finally{active.set(false);}
    }
    private void advanceDay(){if(pending!=null)cursor=following;pending=null;following=null;entityCursor=null;}
    private static IllegalStateException unavailable(){return new IllegalStateException("original catalog day preparation cycle unavailable");}
    @Override public String toString(){return "CatalogDayAnalysisPreparationCycle[redacted]";}
}
