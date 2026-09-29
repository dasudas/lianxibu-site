package com.lianxibu.site.controller.settings;

import java.util.List;

/** Exact Preferences response, with contract-fixed read-only schedule and model. */
public final class SettingsDtos {
    private SettingsDtos() { }
    public record Meta(String request_id) { }
    public record Envelope<T>(T data, Meta meta) { }
    public record ErrorDetail(String code, String message) { }
    public record ErrorEnvelope(ErrorDetail error, Meta meta) { }
    public record OperationalRule(String rule_id, String warning_threshold, String critical_threshold,
                                  String recovery_threshold, String notification_secret_ref,
                                  boolean notification_configured) { }
    public record Preferences(long revision, List<String> follow_keywords, List<String> blocked_keywords,
                              boolean ai_paused, String default_package_id, String model, String timezone,
                              String collection_time, String report_time, String ai_window_start,
                              String ai_window_end, List<OperationalRule> operational_limits) { }
}
