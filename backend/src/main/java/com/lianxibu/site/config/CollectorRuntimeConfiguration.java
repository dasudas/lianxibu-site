package com.lianxibu.site.config;

import com.lianxibu.site.controller.collector.CollectorGateway;
import com.lianxibu.site.controller.collector.JdbcCollectorGateway;
import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.repository.sources.JdbcSourceAdmission;
import com.lianxibu.site.repository.sources.JdbcSourceRunReceipts;
import com.lianxibu.site.repository.sources.JdbcSourceRunStore;
import com.lianxibu.site.repository.sources.JdbcRunManifestStore;
import com.lianxibu.site.repository.sources.JdbcSourceReads;
import com.lianxibu.site.repository.articles.JdbcObservationStore;
import com.lianxibu.site.repository.articles.JdbcArticleWrites;
import com.lianxibu.site.repository.articles.JdbcArticleProjection;
import com.lianxibu.site.service.identity.NodeIdentityService;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import com.lianxibu.site.service.sources.RunLeaseTokenCodec;
import com.lianxibu.site.service.sources.SourceRunLeaseService;
import com.lianxibu.site.service.sources.RunManifestService;
import com.lianxibu.site.service.sources.ProbeResultService;
import com.lianxibu.site.service.sources.SourceIdentityMappingService;
import com.lianxibu.site.service.articles.ArticleCompatibilityWriter;
import com.lianxibu.site.service.articles.ArticleProjectionService;
import com.lianxibu.site.service.articles.ObservationIngressService;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** Explicit collector runtime with host-only lease key; unconfigured activation fails startup. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "site.collector.enabled", havingValue = "true")
public class CollectorRuntimeConfiguration {
    @Bean
    SourceRunLeaseService collectorLeases(DataSource dataSource, PlatformTransactionManager tx,
                                          NodeIdentityService nodes,
                                          @Value("${SITE_RUN_LEASE_KEY_B64:}") String encoded,
                                          @Value("${SITE_RUN_LEASE_KEY_ID:}") String keyId) {
        byte[] key;
        try { key = Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException invalid) { throw new IllegalStateException("collector lease key unavailable"); }
        if (key.length != 32 || keyId == null || !keyId.matches("[A-Za-z0-9._:-]{1,64}"))
            throw new IllegalStateException("collector lease key unavailable");
        Clock clock = Clock.systemUTC();
        var writes = new SharedWriteExecutor(new JdbcSharedWriteEntry(dataSource), tx);
        return new SourceRunLeaseService(new JdbcSourceRunStore(dataSource),
                new JdbcSourceAdmission(dataSource), writes,
                new RunLeaseTokenCodec(new SecretKeySpec(key, "AES"), keyId, new SecureRandom()),
                nodes, new JdbcSourceRunReceipts(dataSource, clock), clock);
    }

    @Bean
    RunManifestService collectorManifests(DataSource dataSource, PlatformTransactionManager tx,
                                          NodeIdentityService nodes) {
        return new RunManifestService(new JdbcRunManifestStore(dataSource),
                new SharedWriteExecutor(new JdbcSharedWriteEntry(dataSource), tx), nodes, Clock.systemUTC());
    }

    @Bean
    CollectorReviewCatalog collectorReviewCatalog(
            @Value("${SITE_COLLECTOR_REVIEW_CATALOG:}") String location) {
        return CollectorReviewCatalog.load(location);
    }

    @Bean
    ObservationIngressService collectorObservations(DataSource dataSource, PlatformTransactionManager tx,
                                                     NodeIdentityService nodes, RunManifestService manifests,
                                                     CollectorReviewCatalog review) {
        Clock clock = Clock.systemUTC();
        var writes = new SharedWriteExecutor(new JdbcSharedWriteEntry(dataSource), tx);
        var runs = new JdbcSourceRunStore(dataSource);
        var mappings = new SourceIdentityMappingService(new JdbcSourceReads(dataSource), tx, review.sources());
        var articles = new ArticleCompatibilityWriter(new JdbcArticleWrites(dataSource), mappings,
                tx, clock, review.articles());
        var projection = new ArticleProjectionService(new JdbcArticleProjection(dataSource),
                review.orderRules(), clock);
        return new ObservationIngressService(new JdbcObservationStore(dataSource), runId -> {
            var row = runs.locked(runId);
            return row == null ? null : new ObservationIngressService.RunSnapshot(row.id(),
                    row.sourceId(), row.businessDate(), row.configVersion(), row.configSnapshotJson(),
                    row.activationStage(), row.deliveryState(), row.windowStart(), row.windowEnd());
        }, writes, nodes, articles, projection, manifests::recomputeAfterMember, clock);
    }

    @Bean
    ProbeResultService collectorProbeResults(DataSource dataSource, PlatformTransactionManager tx,
                                             NodeIdentityService nodes) {
        return new ProbeResultService(dataSource,
                new SharedWriteExecutor(new JdbcSharedWriteEntry(dataSource), tx),
                nodes, Clock.systemUTC());
    }

    @Bean
    CollectorGateway collectorGateway(DataSource dataSource, NodeIdentityService nodes,
                                      SourceRunLeaseService leases, RunManifestService manifests,
                                      ObservationIngressService observations, ProbeResultService probes) {
        return new JdbcCollectorGateway(dataSource, nodes, leases, manifests,
                observations, probes, Clock.systemUTC());
    }
}
