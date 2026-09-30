package com.lianxibu.site.service.sources;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.service.sources.SourcesService.SourceConfig;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Local eligibility rules. Evidence must be supplied by a trusted, persisted run reviewer. */
public final class SourceAdmissionPolicy {
    public record ProbeEvidence(long configVersion, String configSha256, String parserVersion,
                                boolean tlsAndNetworkSafe, boolean rootFormatValid,
                                boolean requiredFieldsValid, boolean stableIdsValid,
                                boolean timeFieldsValid, int validSamples) { }
    public record TrialDay(LocalDate businessDate, long configVersion, String parserVersion,
                           int plannedRequests, int successfulRequests,
                           int maxConsecutiveParseFailures, int leadingParseFailures,
                           int trailingParseFailures, boolean coverageReviewed) { }
    public record PromotionEvidence(long configVersion, String configSha256,
                                    String parserVersion, String usageBasisRef,
                                    String fieldMappingRef, ProbeEvidence probe,
                                    List<TrialDay> days) { }

    private final Clock clock;
    public SourceAdmissionPolicy(Clock clock) {
        if (clock == null) throw new IllegalArgumentException("source admission clock required");
        this.clock = clock;
    }

    public String validatedConfigJson(SourceConfig config) {
        if (config == null || blank(config.adapter()) || blank(config.identityRule())
                || blank(config.windowRule()) || blank(config.parserVersion())
                || config.parserVersion().length() > 64
                || config.entryUrls() == null || config.entryUrls().isEmpty()
                || config.entryUrls().size() > 16 || config.allowedHosts() == null
                || config.allowedHosts().isEmpty() || config.allowedHosts().size() > 32
                || config.maxItems() < 1 || config.maxItems() > 1000
                || config.maxResponseBytes() < 1 || config.maxResponseBytes() > 10_485_760
                || config.validUntil() == null || !config.validUntil().isAfter(clock.instant())) {
            throw new IllegalArgumentException("invalid source config");
        }
        Set<String> hosts = new HashSet<>();
        for (String host : config.allowedHosts()) {
            if (blank(host) || !host.matches("[A-Za-z0-9.-]{1,253}")
                    || !hosts.add(host.toLowerCase(java.util.Locale.ROOT))) {
                throw new IllegalArgumentException("invalid source host allowlist");
            }
        }
        Set<String> entries = new HashSet<>();
        for (String value : config.entryUrls()) {
            URI uri;
            try { uri = URI.create(value); }
            catch (RuntimeException invalid) { throw new IllegalArgumentException("invalid source entry"); }
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null
                    || !hosts.contains(uri.getHost().toLowerCase(java.util.Locale.ROOT))
                    || !entries.add(value)) {
                throw new IllegalArgumentException("invalid source entry");
            }
        }
        ObjectNode value = JsonMapper.shared().createObjectNode();
        value.put("adapter", config.adapter());
        ArrayNode urls = value.putArray("entry_urls");
        config.entryUrls().forEach(urls::add);
        value.put("identity_rule", config.identityRule());
        value.put("window_rule", config.windowRule());
        value.put("parser_version", config.parserVersion());
        value.put("max_items", config.maxItems());
        value.put("max_response_bytes", config.maxResponseBytes());
        ArrayNode allowed = value.putArray("allowed_hosts");
        config.allowedHosts().forEach(allowed::add);
        value.put("valid_until", config.validUntil().toString());
        if (config.authSecretRef() == null) value.putNull("auth_secret_ref");
        else if (!config.authSecretRef().matches("secret:[A-Za-z0-9._/-]{1,120}")) {
            throw new IllegalArgumentException("invalid secret reference");
        } else value.put("auth_secret_ref", config.authSecretRef());
        return new String(CanonicalJson.bytes(value), java.nio.charset.StandardCharsets.UTF_8);
    }

    public boolean validProbe(ProbeEvidence proof, long revision, String configHash,
                              String parserVersion) {
        return proof != null && proof.configVersion() == revision
                && configHash.equals(proof.configSha256())
                && parserVersion.equals(proof.parserVersion())
                && proof.tlsAndNetworkSafe() && proof.rootFormatValid()
                && proof.requiredFieldsValid() && proof.stableIdsValid()
                && proof.timeFieldsValid() && proof.validSamples() >= 3;
    }

    public boolean validPromotion(PromotionEvidence proof, long revision, String configHash,
                                  String parserVersion) {
        if (proof == null || proof.configVersion() != revision
                || !configHash.equals(proof.configSha256())
                || !parserVersion.equals(proof.parserVersion())
                || blank(proof.usageBasisRef()) || blank(proof.fieldMappingRef())
                || !validProbe(proof.probe(), revision, configHash, parserVersion)
                || proof.days() == null || proof.days().size() < 7) return false;
        List<TrialDay> days = new ArrayList<>(proof.days());
        if (days.stream().anyMatch(day -> day == null || day.businessDate() == null)) return false;
        days.sort(Comparator.comparing(TrialDay::businessDate));
        Set<LocalDate> unique = new HashSet<>();
        long planned = 0;
        long successful = 0;
        for (TrialDay day : days) {
            if (day == null || day.businessDate() == null || !unique.add(day.businessDate())
                    || day.configVersion() != revision
                    || !parserVersion.equals(day.parserVersion())
                    || day.plannedRequests() < 1 || day.successfulRequests() < 0
                    || day.successfulRequests() > day.plannedRequests()
                    || day.maxConsecutiveParseFailures() >= 3
                    || day.leadingParseFailures() < 0 || day.trailingParseFailures() < 0
                    || day.leadingParseFailures() > day.maxConsecutiveParseFailures()
                    || day.trailingParseFailures() > day.maxConsecutiveParseFailures()
                    || !day.coverageReviewed()) return false;
        }
        List<TrialDay> window = days.subList(days.size() - 7, days.size());
        for (int index = 0; index < window.size(); index++) {
            TrialDay day = window.get(index);
            if (index > 0 && !window.get(index - 1).businessDate().plusDays(1)
                    .equals(day.businessDate())) return false;
            if (index > 0 && window.get(index - 1).trailingParseFailures()
                    + day.leadingParseFailures() >= 3) return false;
            planned += day.plannedRequests();
            successful += day.successfulRequests();
        }
        return successful * 100 >= planned * 95;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
