package com.lianxibu.site.controller.jobs;

import com.lianxibu.site.service.content.ContentService;
import com.lianxibu.site.service.jobs.PublicReadService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public protocol metadata and a visibility-checked homepage projection. */
@RestController
public final class PublicReadController {
    private static final DateTimeFormatter MICROS = new DateTimeFormatterBuilder().appendInstant(6).toFormatter();
    private final ObjectProvider<PublicReadService> services;

    public PublicReadController(ObjectProvider<PublicReadService> services) {
        this.services = services;
    }

    public record Meta(String request_id) { }
    public record Envelope<T>(T data, Meta meta) { }
    public record ErrorDetail(String code, String message) { }
    public record ErrorEnvelope(ErrorDetail error, Meta meta) { }
    public record Capabilities(String protocol_revision, String contract_version,
                               List<String> supported_features, int max_page_size, int max_observations) { }
    public record Home(List<Map<String, Object>> reports,
                       List<PackageSummary> learning_packages, String default_package_id) { }
    public record PackageSummary(String id, String title, String description, long revision, String state) { }

    @GetMapping(path = "/capabilities", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Envelope<Capabilities>> capabilities(@RequestParam MultiValueMap<String, String> query) {
        if (!query.isEmpty()) throw new ProtocolError();
        return success(new Capabilities("0.12", "0.15", List.of(), 50, 10));
    }

    @GetMapping(path = "/home", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Envelope<Home>> home(@RequestParam MultiValueMap<String, String> query) {
        if (!query.isEmpty()) throw new ProtocolError();
        PublicReadService service = services.getIfAvailable();
        if (service == null) throw new Unavailable();
        var snapshot = service.home();
        if (snapshot == null || snapshot.reports() == null || snapshot.learningPackages() == null
                || snapshot.reports().size() > 50 || snapshot.learningPackages().size() > 50) {
            throw new Unavailable();
        }
        try {
            var reports = snapshot.reports().stream().map(report -> {
                if (!"report".equals(report.kind()) || !"published".equals(report.publicationState())
                        || report.reviewRequired() || report.blockingReasons() == null
                        || !report.blockingReasons().isEmpty()) throw new Unavailable();
                return reportSummary(report);
            }).toList();
            var packages = snapshot.learningPackages().stream().map(value -> {
                if (value == null || !"published".equals(value.state())) throw new Unavailable();
                return new PackageSummary(value.id(), value.title(), value.description(),
                        value.revision(), value.state());
            }).toList();
            if (snapshot.defaultPackageId() != null && packages.stream()
                    .noneMatch(value -> value.id().equals(snapshot.defaultPackageId()))) throw new Unavailable();
            return success(new Home(reports, packages, snapshot.defaultPackageId()));
        } catch (IllegalArgumentException error) {
            throw new Unavailable();
        }
    }

    @ExceptionHandler(Unavailable.class)
    public ResponseEntity<ErrorEnvelope> unavailable() {
        return ResponseEntity.status(503).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(new ErrorEnvelope(new ErrorDetail("temporarily_unavailable", "temporarily_unavailable"),
                        new Meta(UUID.randomUUID().toString())));
    }

    @ExceptionHandler(ProtocolError.class)
    public ResponseEntity<ErrorEnvelope> protocolError() {
        return ResponseEntity.badRequest().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new ErrorEnvelope(new ErrorDetail("protocol_error", "protocol_error"),
                        new Meta(UUID.randomUUID().toString())));
    }

    private static <T> ResponseEntity<Envelope<T>> success(T data) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new Envelope<>(data, new Meta(UUID.randomUUID().toString())));
    }

    private static Map<String, Object> reportSummary(ContentService.ContentView value) {
        var coverage = value.coverage();
        if (coverage == null || coverage.missingInputs() == null || coverage.missingDates() == null
                || coverage.noUpdateDates() == null) throw new Unavailable();
        var result = new LinkedHashMap<String, Object>();
        result.put("id", value.id());
        result.put("kind", value.kind());
        result.put("title", value.title());
        result.put("revision", value.revision());
        result.put("body_revision", value.bodyRevision());
        result.put("publication_state", value.publicationState());
        result.put("published_at", time(value.publishedAt()));
        result.put("input_snapshot_id", value.inputSnapshotId());
        result.put("generation", value.generation());
        result.put("input_cutoff_at", time(value.inputCutoffAt()));
        var wireCoverage = new LinkedHashMap<String, Object>();
        wireCoverage.put("state", coverage.state());
        wireCoverage.put("missing_inputs", coverage.missingInputs().stream().map(input -> {
            var missing = new LinkedHashMap<String, Object>();
            missing.put("kind", input.kind());
            missing.put("id", input.id());
            missing.put("business_date", date(input.businessDate()));
            missing.put("reason", input.reason());
            return missing;
        }).toList());
        wireCoverage.put("missing_dates", coverage.missingDates().stream().map(PublicReadController::date).toList());
        wireCoverage.put("no_update_dates", coverage.noUpdateDates().stream().map(PublicReadController::date).toList());
        result.put("coverage", wireCoverage);
        result.put("review_required", value.reviewRequired());
        result.put("blocking_reasons", value.blockingReasons());
        result.put("affected_by_correction", value.affectedByCorrection());
        result.put("supersedes_job_id", value.supersedesJobId());
        return result;
    }

    private static String date(LocalDate value) { return value == null ? null : value.toString(); }
    private static String time(Instant value) { return value == null ? null : MICROS.format(value); }

    private static final class Unavailable extends RuntimeException { }
    private static final class ProtocolError extends RuntimeException { }
}
