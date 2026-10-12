package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.articles.EntityAnalysisStateService;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Native whole-day allocation. Durable original receipts precede qualification, clocks and current leaves. */
public final class CatalogDayAnalysisGenerationService {
    private final CatalogDayAnalysisEligibilityService eligibility;private final CatalogDayAnalysisReservationLedger ledger;
    private final EntityAnalysisStateService entities;private final JdbcJobRecords records;private final SharedWriteExecutor writes;
    private final Clock clock;private final String category;
    public CatalogDayAnalysisGenerationService(CatalogDayAnalysisEligibilityService eligibility,CatalogDayAnalysisReservationLedger ledger,
            EntityAnalysisStateService entities,JdbcJobRecords records,SharedWriteExecutor writes,Clock clock,String category){
        this.eligibility=Objects.requireNonNull(eligibility);this.ledger=Objects.requireNonNull(ledger);this.entities=Objects.requireNonNull(entities);this.records=Objects.requireNonNull(records);this.writes=Objects.requireNonNull(writes);this.clock=Objects.requireNonNull(clock);this.category=switch(Objects.requireNonNull(category)){case "agent","skill","workflow","model"->category;default->throw invalid();};
    }
    public CatalogDayAnalysisReservationLedger.Stored reserve(LocalDate day,String entity){
        Objects.requireNonNull(day);WireValues.id(entity,false);String key=JdbcCatalogDayAnalysisReservations.key(category,day,entity),action=JdbcCatalogDayAnalysisReservations.action(key);
        return writes.executePrepared(new SharedWriteExecutor.ActionContext(action,JdbcCatalogDayAnalysisReservations.id(key),null),
            k->{if(!action.equals(k))throw invalid();return TransactionSynchronizationManager.isCurrentTransactionReadOnly()?ledger.read(day,entity):ledger.lockAndRead(day,entity);},
            ()->{var original=eligibility.capture(day);Instant now=original==null?null:Objects.requireNonNull(clock.instant()).truncatedTo(ChronoUnit.MICROS);
                return context->{
                    if(original==null)throw invalid();var selected=eligibility.verify(original).stream().filter(value->entity.equals(value.entity().entityId())).findFirst().orElseThrow(CatalogDayAnalysisGenerationService::invalid);
                    if(!category.equals(selected.entity().category()))throw invalid();long revision=selected.header().revision()+(selected.trigger()==CatalogDayAnalysisEligibilityService.Trigger.NEW_ENTITY?1:0);
                    var proposed=CatalogDayAnalysisReservationLedger.propose(original,selected,revision,now);
                    if(selected.trigger()==CatalogDayAnalysisEligibilityService.Trigger.NEW_ENTITY){
                        var state=entities.reserve(entity,category,selected.header().revision(),0);
                        if(!entity.equals(state.entityId())||!category.equals(state.category())||state.generation()!=1||state.revision()!=revision||!"queued".equals(state.state()))throw invalid();
                    }
                    if(!proposed.intentId().equals(records.ensureIntent(new JdbcJobRecords.Intent(proposed.intentId(),selected.logicalKey(),selected.generation(),category+"_analysis",day),now)))throw invalid();
                    var stored=ledger.append(proposed);if(!stored.json().equals(proposed.json()))throw invalid();return stored;
                };
            });
    }
    private static IllegalStateException invalid(){return new IllegalStateException("original catalog day entity analysis reservation unavailable");}
}
