package com.lianxibu.site.config;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.service.articles.ArticleCompatibilityWriter.ReviewedLegacyArticle;
import com.lianxibu.site.service.articles.ArticleProjectionService.TrustedOrderRule;
import com.lianxibu.site.service.sources.SourceIdentityMappingService.ReviewedMapping;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Host-owned reviewed approvals. No production source or article mapping is embedded in Git. */
public record CollectorReviewCatalog(List<ReviewedMapping> sources,
                                     List<ReviewedLegacyArticle> articles,
                                     List<TrustedOrderRule> orderRules) {
    public CollectorReviewCatalog {
        sources = List.copyOf(sources);
        articles = List.copyOf(articles);
        orderRules = List.copyOf(orderRules);
    }
    @Override public String toString() { return "CollectorReviewCatalog[redacted]"; }

    public static CollectorReviewCatalog load(String location) {
        if (location == null || location.isBlank()) return new CollectorReviewCatalog(List.of(), List.of(), List.of());
        try {
            Path path = Path.of(location);
            if (!path.isAbsolute() || Files.isSymbolicLink(path) || !Files.isRegularFile(path)
                    || Files.size(path) > 1_048_576) throw new IllegalStateException("collector review catalog unavailable");
            JsonNode root = WireValues.object(CanonicalJson.parse(Files.readAllBytes(path)),
                    Set.of("sources", "articles", "order_rules"),
                    Set.of("sources", "articles", "order_rules"));
            List<ReviewedMapping> sources = new ArrayList<>();
            for (JsonNode item : bounded(root.get("sources"), 1000)) {
                Set<String> fields = Set.of("source_id", "source_kind", "config_sha256", "entry_url", "category");
                WireValues.object(item, fields, fields);
                sources.add(new ReviewedMapping(text(item, "source_id"), text(item, "source_kind"),
                        text(item, "config_sha256"), text(item, "entry_url"), text(item, "category")));
            }
            List<ReviewedLegacyArticle> articles = new ArrayList<>();
            for (JsonNode item : bounded(root.get("articles"), 100_000)) {
                Set<String> fields = Set.of("article_id", "source_id", "external_id", "source_item_id", "row_sha256");
                WireValues.object(item, fields, fields);
                articles.add(new ReviewedLegacyArticle(text(item, "article_id"), text(item, "source_id"),
                        text(item, "external_id"), text(item, "source_item_id"), text(item, "row_sha256")));
            }
            List<TrustedOrderRule> rules = new ArrayList<>();
            for (JsonNode item : bounded(root.get("order_rules"), 1000)) {
                Set<String> fields = Set.of("source_id", "parser_version", "basis", "domain");
                WireValues.object(item, fields, fields);
                rules.add(new TrustedOrderRule(text(item, "source_id"), text(item, "parser_version"),
                        text(item, "basis"), text(item, "domain")));
            }
            return new CollectorReviewCatalog(sources, articles, rules);
        } catch (IOException | RuntimeException invalid) {
            throw new IllegalStateException("collector review catalog unavailable");
        }
    }

    private static JsonNode bounded(JsonNode value, int max) {
        if (value == null || !value.isArray() || value.size() > max)
            throw new IllegalArgumentException("collector review list invalid");
        return value;
    }
    private static String text(JsonNode value, String field) {
        return WireValues.text(value.get(field));
    }
}
