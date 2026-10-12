package com.lianxibu.site.config;

import com.lianxibu.site.domain.sources.SourceDayRunRegistration;
import com.lianxibu.site.service.jobs.SourceDayCohortSealService;
import com.lianxibu.site.service.jobs.SourceDayRunCoordinatorService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"site.browser.enabled","site.sources.enabled","site.execution.enabled","site.collector.enabled","site.sources.day-roster.enabled"},havingValue="true")
public class SourceDayRunCoordinatorConfiguration {
    @Bean public SourceDayRunCoordinatorService sourceDayRunCoordinator(SourceDayCohortSealService seals,SourceDayRunRegistration.Api runs){return create(seals,runs);}
    public SourceDayRunCoordinatorService create(SourceDayCohortSealService seals,SourceDayRunRegistration.Api runs){return new SourceDayRunCoordinatorService(seals,runs);}
}
