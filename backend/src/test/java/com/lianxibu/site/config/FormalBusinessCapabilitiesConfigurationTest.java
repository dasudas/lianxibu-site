package com.lianxibu.site.config;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.service.jobs.RuntimeCapabilities;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
class FormalBusinessCapabilitiesConfigurationTest {
 static final List<String> FLAGS=List.of("site.browser.enabled","site.collector.enabled","site.content.enabled","site.content.availability.enabled","site.content.propagation.enabled","site.articles.enabled","site.sources.enabled","site.sources.day-roster.enabled","site.execution.enabled","site.execution.preparation.enabled","site.execution.catalog-preparation.enabled","site.execution.report-preparation.enabled","site.execution.report-runtime.enabled","site.execution.ad-hoc.enabled","site.execution.worker.enabled","site.learning.enabled","site.scheduler.enabled");
 @Test void EveryMissingOrDisabledFormalModeWithholdsThePublicFeature(){
  for(String missing:FLAGS)for(boolean absent:List.of(true,false)){
   var properties=new ArrayList<String>();for(String flag:FLAGS)if(!flag.equals(missing))properties.add(flag+"=true");else if(!absent)properties.add(flag+"=false");
   new ApplicationContextRunner().withUserConfiguration(FormalBusinessCapabilitiesConfiguration.class).withPropertyValues(properties.toArray(String[]::new)).run(context->{assertNull(context.getStartupFailure());assertTrue(context.getBeansOfType(RuntimeCapabilities.class).isEmpty(),missing);});
  }
 }
 @Test void FlagsAloneCannotClaimMissingBusinessProviders(){new ApplicationContextRunner().withUserConfiguration(FormalBusinessCapabilitiesConfiguration.class).withPropertyValues(FLAGS.stream().map(flag->flag+"=true").toArray(String[]::new)).run(context->{assertNotNull(context.getStartupFailure());});}
}
