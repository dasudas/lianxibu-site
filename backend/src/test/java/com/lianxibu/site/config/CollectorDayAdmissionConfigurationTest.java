package com.lianxibu.site.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.service.identity.NodeIdentityService;
import com.lianxibu.site.service.sources.SourceDayRunClaimAdmissionService;
import com.lianxibu.site.service.sources.SourceRunLeaseService;
import java.time.*;
import java.util.Base64;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

class CollectorDayAdmissionConfigurationTest {
    private final DataSource db=mock(DataSource.class);
    private final PlatformTransactionManager tx=mock(PlatformTransactionManager.class);
    private final NodeIdentityService nodes=mock(NodeIdentityService.class);
    private final OperationalInputGate gate=mock(OperationalInputGate.class);
    private final SourceDayRunClaimAdmissionService day=mock(SourceDayRunClaimAdmissionService.class);
    private final Clock clock=mock(Clock.class);
    private static final String KEY=Base64.getEncoder().encodeToString(new byte[32]);
    private ApplicationContextRunner context() {return new ApplicationContextRunner().withUserConfiguration(CollectorRuntimeConfiguration.class,CollectorHeartbeatConfiguration.class).withBean(DataSource.class,()->db).withBean(PlatformTransactionManager.class,()->tx).withBean(NodeIdentityService.class,()->nodes);}
    @Test void collectorOffHasNoLeaseBean() {
        context().run(c->{assertNull(c.getStartupFailure());assertTrue(c.getBeansOfType(SourceRunLeaseService.class).isEmpty());});verifyNoInteractions(db,tx,nodes);
    }
    @Test void optionalDayBeanAndMissingDayEachCreateExactlyOneLazyLeaseService() {
        for(boolean configured:new boolean[]{false,true}) {
            var runner=context().withPropertyValues("site.collector.enabled=true","SITE_RUN_LEASE_KEY_B64="+KEY,"SITE_RUN_LEASE_KEY_ID=fictional");
            if(configured) runner=runner.withBean(SourceDayRunClaimAdmissionService.class,()->day);
            runner.run(c->{assertNull(c.getStartupFailure());assertEquals(1,c.getBeansOfType(SourceRunLeaseService.class).size());});
        }
        verifyNoInteractions(db,tx,nodes,day);
    }
    @Test void actualHelperAcceptsOptionalGuardWithoutAssemblyWork() {
        var config=new CollectorRuntimeConfiguration();
        assertNotNull(config.createLeases(db,tx,nodes,gate,KEY,"fictional",day,clock));
        assertNotNull(config.createLeases(db,tx,nodes,gate,KEY,"fictional",null,clock));
        verifyNoInteractions(db,tx,nodes,gate,day,clock);
    }
    @Test void invalidPrivateKeyCannotStartCollector() {
        for(String value:new String[]{"","bad*",Base64.getEncoder().encodeToString(new byte[16])}) {
            context().withPropertyValues("site.collector.enabled=true","SITE_RUN_LEASE_KEY_B64="+value,"SITE_RUN_LEASE_KEY_ID=fictional").run(c->assertNotNull(c.getStartupFailure()));
            assertThrows(RuntimeException.class,()->new CollectorRuntimeConfiguration().createLeases(db,tx,nodes,gate,value,"fictional",day,clock));
        }
        assertThrows(RuntimeException.class,()->new CollectorRuntimeConfiguration().createLeases(db,tx,nodes,gate,KEY,"",day,clock));
        verifyNoInteractions(db,tx,nodes,gate,day,clock);
    }
}
