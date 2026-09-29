package com.lianxibu.site.controller.articles;

import java.util.List;

/** Exact public wire fields for article and entity responses. */
public final class ArticlesDtos {
    private ArticlesDtos() { }

    public record Meta(String request_id) { }
    public record Envelope<T>(T data, Meta meta) { }
    public record ErrorDetail(String code, String message) { }
    public record ErrorEnvelope(ErrorDetail error, Meta meta) { }
    public record Record(String package_id, String object_kind, String object_id, long revision, String status,
                         String self_assessment, String note, String artifact_url, String latest_answer_text,
                         String latest_submission_url, Long confirmed_body_revision, String updated_at,
                         boolean is_read, boolean is_favorite, boolean is_ignored) { }
    public record Article(String id, String title, String summary, String source_id, String source_name,
                          String source_url, String published_at, String observed_at, String topic,
                          String region, String content_status, long revision, Record record) { }
    public record ArticlePage(List<Article> items, long page, int page_size, long total) { }
    public record Entity(String id, String category, String title, String summary, String first_recorded_at,
                         String first_seen_business_date, String last_seen_at, Long occurrence_count,
                         Long repeat_count, String analysis_state, long revision) { }
    public record EntityPage(List<Entity> items, long page, int page_size, long total) { }
    public record ResourceRef(String kind, String id, String package_id) { }
    public record MissingInput(String kind, String id, String business_date, String reason) { }
    public record Coverage(String state, List<MissingInput> missing_inputs, List<String> missing_dates,
                           List<String> no_update_dates) { }
    public record ActionResult(String operation_ref, ResourceRef result_resource_ref, long result_revision,
                               Long generation, String cutoff, Coverage coverage, List<ResourceRef> affected,
                               String next_cursor) { }
}
