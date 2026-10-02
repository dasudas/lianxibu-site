package com.lianxibu.site.controller.sources;

import java.util.List;

/** Exact source/run wire projections. Mutable persistence entities never reach JSON serialization. */
public final class SourcesDtos {
    private SourcesDtos() { }

    public record Meta(String request_id) { }
    public record Envelope<T>(T data, Meta meta) { }
    public record ErrorDetail(String code, String message) { }
    public record ErrorEnvelope(ErrorDetail error, Meta meta) { }
    public record SourceConfig(String adapter, List<String> entry_urls, String identity_rule,
                               String window_rule, String parser_version, long max_items,
                               long max_response_bytes, List<String> allowed_hosts, String valid_until,
                               String auth_secret_ref) { }
    public record Source(String id, String name, String category, long revision, long config_revision,
                         String activation_stage, SourceConfig draft_config, SourceConfig effective_config,
                         String config_valid_until) { }
    public record SourcePage(List<Source> items, long page, int page_size, long total) { }
    public record MissingInput(String kind, String id, String business_date, String reason) { }
    public record Coverage(String state, List<MissingInput> missing_inputs, List<String> missing_dates,
                           List<String> no_update_dates) { }
    public record Run(String run_id, String source_id, String run_kind, String business_date, long generation,
                      String window_start, String window_end, String fetch_mode, String collection_reason,
                      long config_version, long run_revision, String delivery_state, String fetch_state,
                      String analysis_state, Coverage coverage, Long expected_count, long accepted_count,
                      long excluded_count, long pending_count, String barrier_closed_at,
                      boolean late_results_available, String result_business_hash, String manifest_hash) { }
    public record RunPage(List<Run> items, long page, int page_size, long total) { }
    public record RunItem(String id, String run_id, String member_id, String source_item_id, String category,
                          String entity_id, long revision, String terminal_state, String state, Boolean is_new,
                          Boolean high_value, String projection_decision, String failure_reason) { }
    public record RunItemPage(List<RunItem> items, long page, int page_size, long total) { }
    public record ResourceRef(String kind, String id, String package_id) { }
    public record ActionResult(String operation_ref, ResourceRef result_resource_ref, long result_revision,
                               Long generation, String cutoff, Coverage coverage, List<ResourceRef> affected,
                               String next_cursor) { }
    public record ItemCommandRef(String source_id, String run_id, String item_id, long expected_revision,
                                 String action, String command_hash) { }
    public record ItemActionResult(String operation_ref, ResourceRef result_resource_ref, long result_revision,
                                   Long generation, String cutoff, Coverage coverage, List<ResourceRef> affected,
                                   String next_cursor, ItemCommandRef command_ref) { }
}
