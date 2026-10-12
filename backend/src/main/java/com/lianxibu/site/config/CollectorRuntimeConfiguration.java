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
import com.lianxibu.site.service.sources.SourceDayRunClaimAdmissionService;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** Explicit collector runtime with host-only lease key; unconfigured activation fails startup. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "site.collector.enabled", havingValue = "true")
public class CollectorRuntimeConfiguration {
    @Bean
    OperationalScopeCatalog operationalScopes(@Value("${SITE_OPERATIONAL_SCOPES:}") String location) {
        return OperationalScopeCatalog.load(location);
    }

    @Bean
    OperationalInputGate operationalInputGate(DataSource dataSource, PlatformTransactionManager tx,
                                               OperationalScopeCatalog scopes) {
        if(scopes.scopes()==null)return new OperationalInputGate(null);
        Clock clock=Clock.systemUTC();
        var settings=new com.lianxibu.site.service.settings.SettingsJdbcStore(
                new com.lianxibu.site.repository.settings.JdbcSettingsWrites(dataSource),id->false,
                change->{throw new IllegalStateException("read-only operational configuration");},clock);
        var summaries=new com.lianxibu.site.service.jobs.OperationalJdbcStore(
                new com.lianxibu.site.repository.jobs.JdbcOperationalSummaries(dataSource),
                new com.lianxibu.site.repository.jobs.JdbcJobRecords(dataSource),clock);
        var snapshotRead=new org.springframework.transaction.support.TransactionTemplate(tx);
        snapshotRead.setReadOnly(true);
        snapshotRead.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return new OperationalInputGate(new com.lianxibu.site.service.jobs.OperationalAdmissionService(
                ()->snapshotRead.execute(ignored->new com.lianxibu.site.service.jobs.OperationalAdmissionService.Snapshot(
                        settings.operationalConfiguration(),summaries.views().stream().map(com.lianxibu.site.service.jobs.OperationalJdbcStore.View::state).toList())),
                scopes.scopes(),clock));
    }

    @Bean
    SourceRunLeaseService collectorLeases(DataSource dataSource, PlatformTransactionManager tx,
                                          NodeIdentityService nodes, OperationalInputGate inputGate,
                                          ObjectProvider<SourceDayRunClaimAdmissionService> dayAdmission,
                                          @Value("${SITE_RUN_LEASE_KEY_B64:}") String encoded,
                                          @Value("${SITE_RUN_LEASE_KEY_ID:}") String keyId) {
        return createLeases(dataSource,tx,nodes,inputGate,encoded,keyId,
                dayAdmission.getIfAvailable(),Clock.systemUTC());
    }

    public SourceRunLeaseService createLeases(DataSource dataSource,PlatformTransactionManager tx,
            NodeIdentityService nodes,OperationalInputGate inputGate,String encoded,String keyId,
            SourceDayRunClaimAdmissionService dayAdmission,Clock clock) {
        byte[] key;
        try { key = Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException invalid) { throw new IllegalStateException("collector lease key unavailable"); }
        if (key.length != 32 || keyId == null || !keyId.matches("[A-Za-z0-9._:-]{1,64}"))
            throw new IllegalStateException("collector lease key unavailable");
        var writes = new SharedWriteExecutor(new JdbcSharedWriteEntry(dataSource), tx);
        return new SourceRunLeaseService(new JdbcSourceRunStore(dataSource),
                new JdbcSourceAdmission(dataSource), writes,
                new RunLeaseTokenCodec(new SecretKeySpec(key, "AES"), keyId, new SecureRandom()),
                nodes::authorizeBearer, new JdbcSourceRunReceipts(dataSource, clock), clock,inputGate::newRunAllowed,dayAdmission);
    }

    @Bean
    RunManifestService collectorManifests(DataSource dataSource, PlatformTransactionManager tx,
                                          NodeIdentityService nodes) {
        var intentions = new com.lianxibu.site.service.jobs.CollectionIntentService(dataSource, Clock.systemUTC());
        var handoff = new com.lianxibu.site.service.sources.CollectionHandoffService(
                new com.lianxibu.site.repository.sources.JdbcCollectionHandoff(dataSource), intentions::ensure);
        return new RunManifestService(new JdbcRunManifestStore(dataSource),
                new SharedWriteExecutor(new JdbcSharedWriteEntry(dataSource), tx),
                (bearer, sourceId) -> nodes.authorizeBearer(bearer, "run:write", sourceId),
                Clock.systemUTC(), handoff::terminal);
    }

    @Bean
    CollectorReviewCatalog collectorReviewCatalog(
            @Value("${SITE_COLLECTOR_REVIEW_CATALOG:}") String location) {
        return CollectorReviewCatalog.load(location);
    }

    @Bean
    ObservationIngressService collectorObservations(DataSource dataSource, PlatformTransactionManager tx,
                                                     NodeIdentityService nodes, RunManifestService manifests,
                                                     CollectorReviewCatalog review, OperationalInputGate inputGate) {
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
        }, writes, (bearer,sourceId)->nodes.authorizeBearer(bearer,"observation:write",sourceId),
                (input,day)->{
                    var compatible=articles.accept(input);var decided=projection.decide(input,compatible,day);
                    return new ObservationIngressService.ArticleOutcome(decided.articleId(),decided.evidenceRevisionId(),decided.projectionDecision(),decided.articleRevision());
                }, manifests::recomputeAfterMember, clock,inputGate::uploadAllowed);
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
                                      ObservationIngressService observations, ProbeResultService probes,
                                      com.lianxibu.site.service.jobs.CollectorHeartbeatService heartbeats) {
        return new JdbcCollectorGateway(dataSource, nodes, leases, manifests,
                observations, probes, Clock.systemUTC(),heartbeats);
    }
}
