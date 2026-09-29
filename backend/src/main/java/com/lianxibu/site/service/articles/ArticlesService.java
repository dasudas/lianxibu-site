package com.lianxibu.site.service.articles;

import com.lianxibu.site.service.identity.IdentityService.Actor;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Typed business port for private article reads and owner-only entity corrections. */
public interface ArticlesService {
    record Record(String packageId, String objectKind, String objectId, long revision, String status,
                  String selfAssessment, String note, String artifactUrl, String latestAnswerText,
                  String latestSubmissionUrl, Long confirmedBodyRevision, Instant updatedAt,
                  boolean isRead, boolean isFavorite, boolean isIgnored) { }
    record Article(String id, String title, String summary, String sourceId, String sourceName,
                   String sourceUrl, Instant publishedAt, Instant observedAt, String topic, String region,
                   String contentStatus, long revision, Record record) { }
    record ArticlePage(List<Article> items, long page, int pageSize, long total) { }
    record Entity(String id, String category, String title, String summary, Instant firstRecordedAt,
                  LocalDate firstSeenBusinessDate, Instant lastSeenAt, Long occurrenceCount,
                  Long repeatCount, String analysisState, long revision) { }
    record EntityPage(List<Entity> items, long page, int pageSize, long total) { }
    record Assignment(String itemId, String oldEntityId, String newEntityId, long expectedItemRevision) { }
    record EntityAction(long expectedRevision, String action, String reason, List<Assignment> assignments) { }
    record ResourceRef(String kind, String id, String packageId) { }
    record MissingInput(String kind, String id, LocalDate businessDate, String reason) { }
    record Coverage(String state, List<MissingInput> missingInputs, List<LocalDate> missingDates,
                    List<LocalDate> noUpdateDates) { }
    record ActionResult(String operationRef, ResourceRef resultResourceRef, long resultRevision,
                        Long generation, Instant cutoff, Coverage coverage, List<ResourceRef> affected,
                        String nextCursor) { }

    ArticlePage listArticles(Actor actor, String sourceId, String query, String status, long page, int pageSize);
    Article article(Actor actor, String articleId);
    EntityPage listEntities(Actor actor, String category, String query, long page, int pageSize);
    Entity entity(Actor actor, String entityId);
    ActionResult actOnEntity(Actor actor, String entityId, EntityAction command,
                             String idempotencyKey, String csrfToken, String origin);
}
