package com.lianxibu.site.service.articles;

import java.time.Instant;

/** Evidence time controls analysis eligibility; retrieval time never substitutes for it. */
public final class ArticleWindowQualification {
    private ArticleWindowQualification() { }

    public static String exclusion(ArticleCompatibilityWriter.Input input, Instant start, Instant end) {
        if (input == null || start == null || end == null || !start.isBefore(end)) {
            throw new IllegalArgumentException("frozen article window required");
        }
        Instant sourceTime = input.sourceTime();
        if (sourceTime == null) return "time_unknown";
        return sourceTime.isBefore(start) || !sourceTime.isBefore(end) ? "outside_window" : null;
    }
}
