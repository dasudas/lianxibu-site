package com.lianxibu.site.config;

import com.lianxibu.site.repository.records.JdbcRecordReads;
import com.lianxibu.site.repository.records.JdbcRecordWrites;
import com.lianxibu.site.repository.learning.JdbcLearningReads;
import com.lianxibu.site.repository.articles.JdbcArticleReads;
import com.lianxibu.site.service.articles.ArticleReadService;
import com.lianxibu.site.service.learning.LearningRecordEligibility;
import com.lianxibu.site.service.records.*;
import javax.sql.DataSource;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="site.browser.enabled",havingValue="true")
public class PrivateRecordConfiguration {
    @Bean ArticleRecordReadService articleRecords(DataSource source,PlatformTransactionManager tx){
        return new ArticleRecordReadService(new com.lianxibu.site.repository.records.JdbcArticleRecordReads(source),tx);
    }
    @Bean PrivateRecordWriteService privateRecords(DataSource source,PlatformTransactionManager tx){
        var learning=new LearningRecordEligibility(new JdbcLearningReads(source));
        var articles=new ArticleReadService(new JdbcArticleReads(source),tx);
        var store=new RecordJdbcStore(new JdbcRecordWrites(source),new RecordJdbcStore.Visibility(){
            @Override public Long bodyRevision(String pkg,String kind,String id){
                return "article".equals(kind)?articles.article(id).map(JdbcArticleReads.ArticleRow::revision).orElse(null):learning.revision(pkg,kind,id);
            }
            @Override public List<String> routeNodes(String pkg,String id){return learning.routeNodes(pkg,id);}
        });
        return new PrivateRecordWriteService(store,new RecordReadService(new JdbcRecordReads(source),tx),tx,Clock.systemUTC());
    }
}
