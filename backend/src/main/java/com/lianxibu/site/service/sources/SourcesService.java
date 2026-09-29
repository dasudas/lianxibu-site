package com.lianxibu.site.service.sources;

import com.lianxibu.site.service.identity.IdentityService.Actor;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Business port for the nine owner-only source and run operations. */
public interface SourcesService {
    record SourceConfig(String adapter, List<String> entryUrls, String identityRule, String windowRule,
                        String parserVersion, long maxItems, long maxResponseBytes, List<String> allowedHosts,
                        Instant validUntil, String authSecretRef) { }
    record SourceCreate(String id, String name, String category, SourceConfig config) { }
    record SourceWrite(long expectedRevision, String name, String category, SourceConfig config) { }
    record SourceAction(long expectedRevision, String action, String reason) { }
    record Source(String id, String name, String category, long revision, long configRevision,
                  String activationStage, SourceConfig draftConfig, SourceConfig effectiveConfig,
                  Instant configValidUntil) { }
    record SourcePage(List<Source> items, long page, int pageSize, long total) { }
    record MissingInput(String kind, String id, LocalDate businessDate, String reason) { }
    record Coverage(String state, List<MissingInput> missingInputs, List<LocalDate> missingDates,
                    List<LocalDate> noUpdateDates) { }
    record Run(String runId, String sourceId, String runKind, LocalDate businessDate, long generation,
               Instant windowStart, Instant windowEnd, String fetchMode, String collectionReason,
               long configVersion, long runRevision, String deliveryState, String fetchState,
               String analysisState, Coverage coverage, Long expectedCount, long acceptedCount,
               long excludedCount, long pendingCount, Instant barrierClosedAt, boolean lateResultsAvailable,
               String resultBusinessHash, String manifestHash) { }
    record RunPage(List<Run> items, long page, int pageSize, long total) { }
    record RunCreate(String sourceId, LocalDate businessDate, Instant windowStart, Instant windowEnd,
                     String runKind, long configVersion, long expectedRevision, String reason) { }
    record RunItem(String id, String runId, String memberId, String sourceItemId, String category,
                   String entityId, long revision, String terminalState, String state, Boolean isNew,
                   Boolean highValue, String projectionDecision, String failureReason) { }
    record RunItemPage(List<RunItem> items, long page, int pageSize, long total) { }
    record ItemAction(long expectedRevision, String action, String reason, String entityId,
                      String decision, Long expectedArticleRevision, List<String> evidenceRefs) { }
    record ResourceRef(String kind, String id, String packageId) { }
    record ActionResult(String operationRef, ResourceRef resultResourceRef, long resultRevision,
                        Long generation, Instant cutoff, Coverage coverage, List<ResourceRef> affected,
                        String nextCursor) { }

    SourcePage listSources(Actor actor, long page, int pageSize);
    RunPage listRuns(Actor actor, String sourceId, LocalDate businessDate, long page, int pageSize);
    Run run(Actor actor, String runId);
    RunItemPage listItems(Actor actor, String runId, long page, int pageSize);
    Source createSource(Actor actor, SourceCreate command, String idempotencyKey, String csrfToken, String origin);
    Source writeSource(Actor actor, String sourceId, SourceWrite command, String idempotencyKey,
                       String csrfToken, String origin);
    ActionResult sourceAction(Actor actor, String sourceId, SourceAction command, String idempotencyKey,
                              String csrfToken, String origin);
    Run createRun(Actor actor, RunCreate command, String idempotencyKey, String csrfToken, String origin);
    ActionResult itemAction(Actor actor, String runId, String itemId, ItemAction command,
                            String idempotencyKey, String csrfToken, String origin);
}
