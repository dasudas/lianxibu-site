package com.lianxibu.site.service.sources;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.repository.sources.JdbcCollectionHandoff;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Transfers a terminal run to the analysis backlog inside the collector write transaction. */
public final class CollectionHandoffService {
    public record Terminal(Facts facts,java.util.List<String> jobRefs){public Terminal{Objects.requireNonNull(facts);jobRefs=java.util.List.copyOf(jobRefs);}}
    public record Facts(String runId, String sourceId, String runKind, LocalDate businessDate,
                        long generation, String category, String activationStage, long configVersion,
                        String configHash, String manifestHash, String deliveryState, String fetchState,
                        long expected, long accepted, long excluded) {
        public Facts {
            WireValues.id(runId, false); WireValues.id(sourceId, true);
            if (!Set.of("daily", "historical_backfill", "production_qualification", "trial", "correction").contains(runKind)
                    || businessDate == null || generation < 1 || generation > CanonicalJson.MAX_SAFE_INTEGER
                    || !Set.of("news_tech", "agent", "skill", "workflow", "model").contains(category)
                    || !Set.of("trial", "production").contains(activationStage)
                    || configVersion < 1 || configVersion > CanonicalJson.MAX_SAFE_INTEGER
                    || configHash == null || !configHash.matches("[0-9a-f]{64}")
                    || manifestHash == null || !manifestHash.matches("[0-9a-f]{64}")
                    || !Set.of("complete", "partial", "no_usable_input", "empty_success").contains(deliveryState)
                    || !Set.of("succeeded", "failed", "partial").contains(fetchState)
                    || expected < 0 || expected > CanonicalJson.MAX_SAFE_INTEGER
                    || accepted < 0 || excluded < 0 || accepted > expected || excluded != expected - accepted
                    || "complete".equals(deliveryState) && (accepted == 0 || excluded != 0)
                    || "partial".equals(deliveryState) && (accepted == 0 || excluded == 0)
                    || "empty_success".equals(deliveryState) && (expected != 0 || !"succeeded".equals(fetchState))
                    || "no_usable_input".equals(deliveryState) && (accepted != 0 || expected == 0 && "succeeded".equals(fetchState)))
                throw new IllegalArgumentException("terminal collection facts unavailable");
        }
    }
    @FunctionalInterface public interface Intentions { String ensure(Facts facts); }
    private final JdbcCollectionHandoff store;
    private final Intentions intentions;
    public CollectionHandoffService(JdbcCollectionHandoff store, Intentions intentions) {
        this.store = Objects.requireNonNull(store); this.intentions = Objects.requireNonNull(intentions);
    }
    public void terminal(String runId) {
        var row=locked(runId);
        if ("probe".equals(row.runKind())) return;
        var facts=facts(row);
        store.link(row, WireValues.id(intentions.ensure(facts), false));
    }
    public Terminal terminalFacts(String runId){
        var row=locked(runId);
        if("probe".equals(row.runKind()))throw new IllegalStateException("collection run unavailable");
        return new Terminal(facts(row),JdbcCollectionHandoff.references(row.jobRefs()));
    }
    private JdbcCollectionHandoff.Row locked(String runId){
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("collection handoff requires the collector write transaction");
        var row = store.locked(WireValues.id(runId, false));
        if (row == null||!runId.equals(row.runId())||!row.sourceId().equals(row.sourceIdentity())) throw new IllegalStateException("collection run unavailable");
        return row;
    }
    private static Facts facts(JdbcCollectionHandoff.Row row){
        if (row.pending() != 0) throw new IllegalStateException("collection run incomplete");
        return new Facts(row.runId(), row.sourceId(), row.runKind(), row.businessDate(),
                row.generation(), row.category(), row.activationStage(), row.configVersion(),
                row.configHash(), row.manifestHash(), row.deliveryState(), row.fetchState(),
                row.expected(), row.accepted(), row.excluded());
    }
}
