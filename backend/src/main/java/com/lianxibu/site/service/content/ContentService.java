package com.lianxibu.site.service.content;

import com.lianxibu.site.service.identity.IdentityService.Actor;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Typed port for ten content HTTP operations; implementations are introduced by later tasks. */
public interface ContentService {
    record MissingInput(String kind, String id, LocalDate businessDate, String reason) { }
    record Coverage(String state, List<MissingInput> missingInputs, List<LocalDate> missingDates,
                    List<LocalDate> noUpdateDates) { }
    record Section(String heading, String text, List<String> citationIds) { }
    record LearningTask(String packageId, String objectKind, String objectId, String title, String reason) { }
    sealed interface ContentBody permits ReportBody, InterpretationBody { }
    record ReportBody(String title, List<Section> sections, List<String> citationIds, String type,
                      Instant periodStart, Instant periodEnd, String timezone, LearningTask learningTask)
            implements ContentBody { }
    record InterpretationBody(String title, List<Section> sections, List<String> citationIds,
                              String category, String entityId) implements ContentBody { }
    record Citation(String id, String kind, String title, String sourceUrl, Long revision, String bodyHash) { }
    record MetricSummary(String benchmark, String benchmarkVersion, String task, String score,
                         String unit, Instant evaluatedAt, boolean higherIsBetter,
                         String evaluationScope, String provenance) { }
    record InterpretationProfile(String name, String provider, List<String> tags, String description,
                                 Instant evidenceTime, List<MetricSummary> metrics, Long stars,
                                 Long starsGrowth) { }
    record ContentView(String id, String kind, String title, long revision, long bodyRevision,
                       String publicationState, Instant publishedAt, String inputSnapshotId,
                       Long generation, Instant inputCutoffAt, Coverage coverage, boolean reviewRequired,
                       List<String> blockingReasons, boolean affectedByCorrection, String supersedesJobId,
                       InterpretationProfile interpretation, ContentBody body, List<Citation> citations) { }
    record ContentPage(List<ContentView> items, long page, int pageSize, long total) { }
    record ContentWrite(long expectedRevision, ContentBody body) { }
    record ContentAction(long expectedRevision, String action, String reason, String withdrawalReason) { }
    record EvidenceAction(long expectedValidityRevision, String action, String reason, String invalidationId) { }
    record ContentVersionKey(String kind, String contentKind, String contentId, long revision,
                             String bodyHash, String inputJobId) { }
    record VersionSummary(ContentVersionKey target, String versionKind, long validityRevision,
                          boolean effectiveValid) { }
    record VersionPage(List<VersionSummary> items, long page, int pageSize, long total) { }
    record VersionAction(long expectedValidityRevision, String action, String reason,
                         ContentVersionKey target, String invalidationId) { }
    record GenerationIntentWrite(String kind, Instant periodStart, Instant periodEnd, String timezone,
                                 String mode, Long intentRevision, String reason) { }
    record ResourceRef(String kind, String id, String packageId) { }
    record ActionResult(String operationRef, ResourceRef resultResourceRef, long resultRevision,
                        Long generation, Instant cutoff, Coverage coverage, List<ResourceRef> affected,
                        String nextCursor) { }
    record Usage(Long inputTokens, Long outputTokens, String cost) { }
    record AttemptSummary(String id, long attemptNo, String state, Usage usage,
                          Instant slotReleasedAt, String releaseReason) { }
    record Job(String id, String recordKind, long revision, String taskType, long priority,
               String state, String intentId, Long intentRevision, String contentId, String reportMode,
               String inputState, String inputSnapshotId, Instant inputCutoffAt, Coverage coverage,
               Instant nextEligibleAt, long callCount, Long maxAttempts, long remainingAttempts,
               boolean retryExhausted, String slotState, long slotRevision, String occupiedAttemptId,
               String externalResultState, Instant slotReleasedAt, String releaseReason,
               List<AttemptSummary> attempts, List<String> waitingReasons) { }

    ContentPage reports(String type, long page, int pageSize);
    ContentView report(String id);
    ContentPage interpretations(String kind, long page, int pageSize);
    ContentView interpretation(String id);
    ContentView adminContent(Actor actor, String kind, String id);
    ContentView write(Actor actor, String kind, String id, ContentWrite command,
                      String idempotencyKey, String csrfToken, String origin);
    ActionResult action(Actor actor, String kind, String id, ContentAction command,
                        String idempotencyKey, String csrfToken, String origin);
    ActionResult evidenceAction(Actor actor, String kind, String id, EvidenceAction command,
                                String idempotencyKey, String csrfToken, String origin);
    VersionPage referencedVersions(Actor actor, String kind, String id, long page, int pageSize);
    ActionResult versionAction(Actor actor, String kind, String id, VersionAction command,
                               String idempotencyKey, String csrfToken, String origin);
    Job generationIntent(Actor actor, GenerationIntentWrite command,
                         String idempotencyKey, String csrfToken, String origin);
}
