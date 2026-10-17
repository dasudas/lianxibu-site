package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Original whole-day analysis completion at a cutoff; no current content, entity or source lookup. */
public final class CatalogDayAnalysisCompletionService {
    public record Completed(CatalogDayAnalysisReservationLedger.Stored reservation,
                            ContentVersionKey version,String inputHash,String bodyJson,
                            String snapshotJson,Instant availableAt) {
        public Completed { Objects.requireNonNull(reservation);Objects.requireNonNull(version);Objects.requireNonNull(inputHash);Objects.requireNonNull(bodyJson);Objects.requireNonNull(snapshotJson);Objects.requireNonNull(availableAt); }
        @Override public String toString(){return "CompletedCatalogDayAnalysis[redacted]";}
    }
    private final CatalogAnalysisPreparedInputService prepared;
    private final JdbcFrozenContentVersions originals;
    private final FrozenContentVersionService versions;
    private final JdbcCatalogAnalysisResultVersions resultVersions;
    private final JdbcResultState results;
    private final TransactionTemplate reads;
    private final String category;
    public CatalogDayAnalysisCompletionService(DataSource source,PlatformTransactionManager manager,Clock clock,String category){
        this(source,manager,clock,category,new CatalogAnalysisPreparedInputService(source,manager,clock,category));
    }
    CatalogDayAnalysisCompletionService(DataSource source,PlatformTransactionManager manager,Clock clock,String category,CatalogAnalysisPreparedInputService originalPrepared){
        Objects.requireNonNull(source);Objects.requireNonNull(manager);Objects.requireNonNull(clock);
        this.category=switch(Objects.requireNonNull(category)){case "agent","skill","workflow","model"->category;default->throw invalid();};
        prepared=Objects.requireNonNull(originalPrepared);
        originals=new JdbcFrozenContentVersions(source);versions=new FrozenContentVersionService(originals);
        resultVersions=new JdbcCatalogAnalysisResultVersions(source);results=new JdbcResultState(source);
        reads=new TransactionTemplate(manager);reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    /** Discover through original attempts/APPLIED receipts, then verify the exact immutable completion. */
    public Completed find(LocalDate day,String entity,Instant cutoff){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();
        return reads.execute(s->read(day,entity,cutoff));
    }
    public Completed read(LocalDate day,String entity,Instant cutoff){
        readonly();if(day==null||entity==null||entity.isBlank()||cutoff==null||cutoff.getNano()%1000!=0)throw invalid();
        var original=prepared.read(day,entity);if(original==null)return null;
        var candidates=resultVersions.read(original.input().ownerId());if(candidates.isEmpty())return null;
        return completed(original,day,entity,candidates.getFirst(),cutoff);
    }
    /** Own one read-only repeatable-read snapshot, including original INPUT and committed result. */
    public Completed find(LocalDate day,String entity,ContentVersionKey target,Instant cutoff){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();
        return reads.execute(s->read(day,entity,target,cutoff));
    }
    /** Borrow the report provider's existing RC/RR snapshot. This value grants no publication permission. */
    public Completed read(LocalDate day,String entity,ContentVersionKey target,Instant cutoff){
        readonly();
        if(day==null||entity==null||entity.isBlank()||target==null||!category.equals(target.contentKind())||cutoff==null||cutoff.getNano()%1000!=0)throw invalid();
        var original=prepared.read(day,entity);if(original==null)return null;
        return completed(original,day,entity,target,cutoff);
    }
    private Completed completed(CatalogAnalysisPreparedInputService.Prepared original,LocalDate day,String entity,ContentVersionKey target,Instant cutoff){
        readonly();
        if(day==null||entity==null||entity.isBlank()||target==null||!category.equals(target.contentKind())||cutoff==null||cutoff.getNano()%1000!=0)throw invalid();
        var input=original.input();var reservation=original.reservation();
        var context=FrozenApplicationContext.fromJson(CanonicalJson.parse(input.snapshotJson()).get(FrozenApplicationContext.FIELD));
        if(!category.equals(reservation.category())||!entity.equals(reservation.entityId())||!day.equals(reservation.day())
            ||!target.inputJobId().equals(input.ownerId())||!target.contentId().equals(context.contentId())||!context.referencesComplete()
            ||context.generation()!=reservation.generation()||!entity.equals(context.input(input.ownerId(),"completion-check",input.inputHash(),0).targetEntityId()))throw invalid();
        var proof=versions.find(target).orElse(null);if(proof==null)return null;
        var root=results.root(input.ownerId());
        if(!"succeeded".equals(root.state())||!"APPLIED".equals(root.application())||root.appliedAt()==null||!input.inputHash().equals(root.hash()))throw invalid();
        if(!proof.version().equals(target)||!proof.context().json().equals(context.json())||!proof.inputHash().equals(input.inputHash())
            ||!proof.snapshotJson().equals(input.snapshotJson()))throw invalid();
        var nativeProof=originals.read(target);if(nativeProof==null||nativeProof.recordedAt()==null)throw invalid();
        var observed=proof.availableAt();if(observed==null)return null;
        if(observed.isBefore(nativeProof.recordedAt())||observed.isBefore(reservation.reservedAt())||observed.getNano()%1000!=0)throw invalid();
        if(observed.isAfter(cutoff))return null;
        return new Completed(reservation,target,input.inputHash(),proof.bodyJson(),proof.snapshotJson(),observed);
    }
    private static void readonly(){var isolation=TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()
            ||!Set.of(TransactionDefinition.ISOLATION_READ_COMMITTED,TransactionDefinition.ISOLATION_REPEATABLE_READ).contains(isolation==null?-1:isolation))throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("original catalog analysis completion unavailable");}
    @Override public String toString(){return "CatalogDayAnalysisCompletionService[redacted]";}
}
