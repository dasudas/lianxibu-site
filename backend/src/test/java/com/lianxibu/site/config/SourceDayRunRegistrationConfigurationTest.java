package com.lianxibu.site.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lianxibu.site.domain.sources.SourceDayRunRegistration;
import com.lianxibu.site.service.sources.SourceDayRunRegistrationService;
import java.time.Clock;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

class SourceDayRunRegistrationConfigurationTest {
    private static final List<String> FLAGS=List.of("site.browser.enabled","site.sources.enabled","site.execution.enabled","site.collector.enabled","site.sources.day-roster.enabled");
    private final DataSource source=mock(DataSource.class);private final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);private final OperationalInputGate gate=mock(OperationalInputGate.class);
    private ApplicationContextRunner base(){return new ApplicationContextRunner().withUserConfiguration(SourceDayRunRegistrationConfiguration.class).withBean(DataSource.class,()->source).withBean(PlatformTransactionManager.class,()->manager);}
    @Test void defaultAndEveryMissingFlagHaveNoRegistryOrSideEffects(){
        var runner=base().withBean(OperationalInputGate.class,()->gate);runner.run(c->{assertNull(c.getStartupFailure());assertEquals(0,c.getBeansOfType(SourceDayRunRegistration.Api.class).size());});
        for(var omitted:FLAGS)runner.withPropertyValues(FLAGS.stream().filter(f->!f.equals(omitted)).map(f->f+"=true").toArray(String[]::new)).run(c->{assertNull(c.getStartupFailure());assertEquals(0,c.getBeansOfType(SourceDayRunRegistration.Api.class).size());});verifyNoInteractions(source,manager,gate);
    }
    @Test void allFlagsProduceExactlyOneActualApiWithNoConstructorWork(){
        base().withBean(OperationalInputGate.class,()->gate).withPropertyValues(FLAGS.stream().map(f->f+"=true").toArray(String[]::new)).run(c->{assertNull(c.getStartupFailure());assertEquals(1,c.getBeansOfType(SourceDayRunRegistration.Api.class).size());assertEquals(1,c.getBeansOfType(SourceDayRunRegistrationService.class).size());});
        Clock clock=mock(Clock.class);assertNotNull(new SourceDayRunRegistrationConfiguration().create(source,manager,gate,clock));verifyNoInteractions(source,manager,gate,clock);
    }
    @Test void missingAdmissionGateCannotBecomePermissiveDefault(){
        base().withPropertyValues(FLAGS.stream().map(f->f+"=true").toArray(String[]::new)).run(c->assertNotNull(c.getStartupFailure()));
        assertThrows(NullPointerException.class,()->new SourceDayRunRegistrationConfiguration().create(source,manager,null,mock(Clock.class)));verifyNoInteractions(source,manager,gate);
    }
}
