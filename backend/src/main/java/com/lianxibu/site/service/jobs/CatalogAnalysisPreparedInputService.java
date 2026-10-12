package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Original permanent INPUT/request recovery, independent of current sources, entities, settings and clocks. */
public final class CatalogAnalysisPreparedInputService {
    public record Prepared(CatalogDayAnalysisReservationLedger.Stored reservation,GenerationFlowService.Frozen input){@Override public String toString(){return "CatalogAnalysisPreparedInput[redacted]";}}
    private final DataSource source;private final CatalogDayAnalysisReservationLedger reservations;private final JdbcCatalogAnalysisPreparedInputs preparations;
    private final JdbcFrozenInputs inputs;private final Clock clock;private final String category;private final TransactionTemplate reads;
    public CatalogAnalysisPreparedInputService(DataSource source,PlatformTransactionManager manager,Clock clock,String category){
        this.source=Objects.requireNonNull(source);this.clock=Objects.requireNonNull(clock);this.category=switch(Objects.requireNonNull(category)){case "agent","skill","workflow","model"->category;default->throw invalid();};reservations=new CatalogDayAnalysisReservationLedger(new JdbcCatalogDayAnalysisReservations(source),category);preparations=new JdbcCatalogAnalysisPreparedInputs(source);inputs=new JdbcFrozenInputs(source);reads=new TransactionTemplate(Objects.requireNonNull(manager));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public Prepared find(LocalDate day,String entity){if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();return reads.execute(s->read(day,entity));}
    /** Borrowed RC/RR original receipt lookup, including SharedWriteExecutor's committed-result callbacks. */
    public Prepared read(LocalDate day,String entity){
        var reservation=reservations.read(day,entity);return original(reservation);
    }
    public Prepared lockInWriter(LocalDate day,String entity){return original(reservations.lockAndRead(day,entity));}
    private Prepared original(CatalogDayAnalysisReservationLedger.Stored reservation){
        if(reservation==null)return null;String root=JdbcCatalogAnalysisPreparedInputs.root(reservation.intentId()),action=JdbcCatalogAnalysisPreparedInputs.action(root);String raw=preparations.read(root,action);if(raw==null)return null;var receipt=CanonicalJson.parse(raw);String hash=WireValues.sha256(receipt.get("input_hash"));if(!root.equals(WireValues.id(receipt.get("owner_id"),false))||!"queued".equals(WireValues.text(receipt.get("state"))))throw invalid();preparations.originalAttemptBounded(root);var snapshot=CanonicalJson.parse(inputs.read(root,hash));var base=CatalogAnalysisInputProfile.complete(snapshot);if(!reservation.json().equals(JdbcFrozenInputs.canonical(snapshot.get(CatalogAnalysisInputProfile.FIELD).get("reservation")))||!category.equals(reservation.category()))throw invalid();
        var committed=new GenerationJdbcStore(source,clock,JdbcCatalogAnalysisPreparedInputs.ACTOR,root,base.command()).committed(action);if(committed==null||committed.snapshotJson()==null||committed.requestJson()==null||!hash.equals(committed.inputHash())||!JdbcFrozenInputs.canonical(snapshot).equals(committed.snapshotJson()))throw invalid();return new Prepared(reservation,committed);
    }
    private static IllegalStateException invalid(){return new IllegalStateException("original complete catalog analysis input unavailable");}
}
