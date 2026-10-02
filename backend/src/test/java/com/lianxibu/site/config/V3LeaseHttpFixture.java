package com.lianxibu.site.config;

import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.repository.sources.*;
import com.lianxibu.site.service.identity.NodeIdentityService;
import com.lianxibu.site.service.jobs.*;
import com.lianxibu.site.service.sources.*;
import com.lianxibu.site.controller.collector.CollectorGateway;
import com.lianxibu.site.controller.collector.JdbcCollectorGateway;
import com.lianxibu.site.service.articles.ObservationIngressService;
import java.time.*;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.security.SecureRandom;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;

/** Only the collector HTTP test imports this fixture. All persistent providers are real. */
@TestConfiguration(proxyBeanMethods=false)
class V3LeaseHttpFixture {
    static final class FixtureClock extends Clock {
        private final AtomicReference<Instant> fixed=new AtomicReference<>();
        void freeze(Instant at){fixed.set(at);}
        @Override public Instant instant(){Instant at=fixed.get();return at==null?Instant.now():at;}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return Clock.fixed(instant(),zone);}
    }
    @Bean FixtureClock fictionalV3Clock(){return new FixtureClock();}
    @Bean @Primary SourceRunLeaseService fictionalV3Leases(DataSource dataSource,PlatformTransactionManager tx,
            NodeIdentityService nodes,OperationalInputGate inputGate,FixtureClock clock){
        byte[] key=new byte[32];Arrays.fill(key,(byte)'x');
        return new SourceRunLeaseService(new JdbcSourceRunStore(dataSource),new JdbcSourceAdmission(dataSource),
            new SharedWriteExecutor(new JdbcSharedWriteEntry(dataSource),tx),
            new RunLeaseTokenCodec(new SecretKeySpec(key,"AES"),"fixture",new SecureRandom()),
            nodes::authorizeBearer,new JdbcSourceRunReceipts(dataSource,clock),clock,inputGate::newRunAllowed);
    }
    @Bean @Primary CollectorGateway fictionalV3Gateway(DataSource dataSource,NodeIdentityService nodes,
            SourceRunLeaseService leases,RunManifestService manifests,ObservationIngressService observations,
            ProbeResultService probes,FixtureClock clock){
        return new JdbcCollectorGateway(dataSource,nodes,leases,manifests,observations,probes,clock);
    }
}
