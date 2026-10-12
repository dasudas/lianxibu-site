package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.content.FrozenPublicMetadata;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Freezes one complete native day/entity analysis INPUT. Request construction is outside every writer. */
public final class CatalogDayAnalysisPreparationService {
    private final DataSource source;
    private final CatalogAnalysisPreparedInputService originals;
    private final CatalogDayAnalysisInputPermitService permits;
    private final SharedWriteExecutor writes;
    private final GenerationFlowService.SettingsSnapshotSource settings;
    private final AnalysisPublicMetadataEnricher metadata;
    private final GenerationRequestBuilder requests;
    private final Clock clock;
    private final BooleanSupplier allowed;
    private final String category;

    public CatalogDayAnalysisPreparationService(DataSource source,CatalogAnalysisPreparedInputService originals,
            CatalogDayAnalysisInputPermitService permits,SharedWriteExecutor writes,
            GenerationFlowService.SettingsSnapshotSource settings,AnalysisPublicMetadataEnricher metadata,
            GenerationRequestBuilder requests,Clock clock,BooleanSupplier allowed,String category) {
        this.source=Objects.requireNonNull(source);this.originals=Objects.requireNonNull(originals);
        this.permits=Objects.requireNonNull(permits);this.writes=Objects.requireNonNull(writes);
        this.settings=Objects.requireNonNull(settings);this.metadata=Objects.requireNonNull(metadata);
        this.requests=Objects.requireNonNull(requests);this.clock=Objects.requireNonNull(clock);
        this.allowed=Objects.requireNonNull(allowed);
        this.category=switch(Objects.requireNonNull(category)){case "agent","skill","workflow","model"->category;default->throw invalid();};
    }

    public CatalogAnalysisPreparedInputService.Prepared prepare(LocalDate day,String entity) {
        Objects.requireNonNull(day);WireValues.id(entity,false);
        String key=JdbcCatalogDayAnalysisReservations.key(category,day,entity);
        var context=new SharedWriteExecutor.ActionContext("catalog-day-analysis-input:"+key,
                JdbcFrozenInputs.stableId("catalog-analysis-input",key),null);
        return writes.executePrepared(context,k->{
            if(!context.actionKey().equals(k))throw invalid();
            return TransactionSynchronizationManager.isCurrentTransactionReadOnly()?originals.read(day,entity):originals.lockInWriter(day,entity);
        },()->{
            try{return build(day,entity,context);}
            catch(RuntimeException failure){
                // Another caller may finish while this caller captures mutable qualification.
                var committed=originals.find(day,entity);if(committed==null)throw failure;
                return ignored->committed;
            }
        });
    }

    private SharedWriteExecutor.Work<CatalogAnalysisPreparedInputService.Prepared> build(LocalDate day,String entity,
            SharedWriteExecutor.ActionContext action) {
        if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();
        var captured=Objects.requireNonNull(permits.capture(day,entity));var reservation=captured.receipt();
        if(!category.equals(reservation.category())||!day.equals(reservation.day())||!entity.equals(reservation.entityId()))throw invalid();
        Instant now=Objects.requireNonNull(clock.instant()).truncatedTo(ChronoUnit.MICROS),cutoff=captured.dayEvidence().original().cutoff();
        if(now.isBefore(cutoff)||now.isBefore(reservation.reservedAt()))throw invalid();
        String root=JdbcCatalogAnalysisPreparedInputs.root(reservation.intentId()),prepareAction=JdbcCatalogAnalysisPreparedInputs.action(root);
        var frozenSettings=writes.execute(action,k->null,ignored->{
            requireAllowed();permits.verify(captured);return Objects.requireNonNull(settings.freeze());
        });
        var base=CatalogAnalysisInputProfile.create(captured,frozenSettings);
        var extension=writes.execute(action,k->null,ignored->{
            requireAllowed();permits.verify(captured);requireSettings(frozenSettings);
            return metadata.fields(root,base.command(),cutoff,CanonicalJson.parse(base.snapshotJson()));
        });
        WireValues.object(extension,Set.of(FrozenPublicMetadata.FIELD),Set.of(FrozenPublicMetadata.FIELD));
        var full=(ObjectNode)CanonicalJson.parse(base.snapshotJson());full.set(FrozenPublicMetadata.FIELD,extension.get(FrozenPublicMetadata.FIELD).deepCopy());
        var complete=CatalogAnalysisInputProfile.complete(full);var command=complete.command();
        String hash=CanonicalJson.hash(CanonicalJson.Domain.INPUT,full),request=requests.build(command.stage(),complete.snapshotJson(),hash);
        List<String> missing=full.get("missing").valueStream().map(JsonNode::asText).toList();
        var input=new GenerationFlowService.Frozen(root,command.logicalKey(),command.generation(),command.stage(),cutoff,
                full.get("coverage_state").asText(),missing,complete.snapshotJson(),hash,request,command.stage().priority(),now,"queued");
        return ignored->{
            requireAllowed();permits.verify(captured);requireSettings(frozenSettings);
            var current=metadata.fields(root,command,cutoff,CanonicalJson.parse(base.snapshotJson()));
            if(!Arrays.equals(CanonicalJson.bytes(extension),CanonicalJson.bytes(current)))throw invalid();
            new GenerationJdbcStore(source,Clock.fixed(now,ZoneOffset.UTC),JdbcCatalogAnalysisPreparedInputs.ACTOR,root,command).save(input,prepareAction);
            var stored=originals.lockInWriter(day,entity);
            if(stored==null||!input.equals(stored.input())||!reservation.json().equals(stored.reservation().json()))throw invalid();
            return stored;
        };
    }
    private void requireAllowed(){if(!allowed.getAsBoolean())throw new GenerationFlowService.CapacityGuarded();}
    private void requireSettings(FrozenGenerationSettings original){if(!original.equals(settings.freeze()))throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("original catalog day analysis preparation unavailable");}
}
