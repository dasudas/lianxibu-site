package com.lianxibu.site.config;
import com.lianxibu.site.service.jobs.OwnerAdHocQuestionReadService;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
/** Reading an accepted question remains available when preparation is paused. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"site.browser.enabled","site.content.enabled","site.execution.enabled"},havingValue="true")
public class OwnerAdHocQuestionRuntimeConfiguration {
 @Bean OwnerAdHocQuestionReadService ownerAdHocQuestionReads(DataSource source,PlatformTransactionManager tx){return new OwnerAdHocQuestionReadService(source,tx);}
}
