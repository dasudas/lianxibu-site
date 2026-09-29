package com.lianxibu.site.controller.records;

import java.util.List;

/** Exact private-record wire response. */
public final class RecordsDtos {
    private RecordsDtos() { }
    public record Meta(String request_id) { }
    public record Envelope<T>(T data, Meta meta) { }
    public record ErrorDetail(String code, String message) { }
    public record ErrorEnvelope(ErrorDetail error, Meta meta) { }
    public record Record(String package_id, String object_kind, String object_id, long revision, String status,
                         String self_assessment, String note, String artifact_url, String latest_answer_text,
                         String latest_submission_url, Long confirmed_body_revision, String updated_at,
                         boolean is_read, boolean is_favorite, boolean is_ignored) { }
    public record RecordPage(List<Record> items, long page, int page_size, long total) { }
}
