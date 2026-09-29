package com.lianxibu.site.service.records;

import com.lianxibu.site.service.identity.IdentityService.Actor;
import java.time.Instant;
import java.util.List;

/** Private per-user record port; implementations enforce actor ownership. */
public interface RecordsService {
    record Record(String packageId, String objectKind, String objectId, long revision, String status,
                  String selfAssessment, String note, String artifactUrl, String latestAnswerText,
                  String latestSubmissionUrl, Long confirmedBodyRevision, Instant updatedAt,
                  boolean isRead, boolean isFavorite, boolean isIgnored) { }
    record RecordPage(List<Record> items, long page, int pageSize, long total) { }
    record RecordWrite(long expectedRevision, String status, String selfAssessment, String note,
                       String artifactUrl, String packageId, String objectKind, Boolean isRead,
                       Boolean isFavorite, Boolean isIgnored, String latestAnswerText,
                       String latestSubmissionUrl) { }

    RecordPage list(Actor actor, String packageId, String kind, long page, int pageSize);
    Record write(Actor actor, String kind, String id, RecordWrite command,
                 String idempotencyKey, String csrfToken, String origin);
}
