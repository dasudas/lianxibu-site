package com.lianxibu.site.config;

import com.lianxibu.site.service.jobs.SourceDayCohortSealService;
import com.lianxibu.site.service.jobs.SourceDayCollectionAdmissionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"site.browser.enabled","site.sources.enabled","site.execution.enabled","site.collector.enabled","site.sources.day-roster.enabled"},havingValue="true")
public class SourceDayCollectionAdmissionConfiguration {
    @Bean public SourceDayCollectionAdmissionService sourceDayCollectionAdmission(SourceDayCohortSealService seals){return create(seals);}
    public SourceDayCollectionAdmissionService create(SourceDayCohortSealService seals){return new SourceDayCollectionAdmissionService(seals);}
}
