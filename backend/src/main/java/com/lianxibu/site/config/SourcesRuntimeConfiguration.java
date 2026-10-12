package com.lianxibu.site.config;

import com.lianxibu.site.repository.sources.JdbcOwnerSourceReads;
import com.lianxibu.site.service.sources.OwnerSourceReadService;
import com.lianxibu.site.repository.sources.*;
import com.lianxibu.site.service.sources.*;
import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import java.time.Clock;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"site.browser.enabled","site.sources.enabled"},havingValue="true")
public class SourcesRuntimeConfiguration {
    @Bean OwnerSourceReadService ownerSources(DataSource source,PlatformTransactionManager tx){
        return new OwnerSourceReadService(new JdbcOwnerSourceReads(source,tx));
    }
    @Bean SourcesService sourceRegistry(DataSource source,PlatformTransactionManager tx,OwnerSourceReadService reads,
            org.springframework.beans.factory.ObjectProvider<OperationalInputGate> gates){
        var clock=Clock.systemUTC();var writer=new SharedWriteExecutor(new JdbcSharedWriteEntry(source),tx);
        var identity=new SourceIdentityMappingService(new JdbcSourceReads(source),tx,List.of());
        var runReads=new SourceRunReadService(new JdbcSourceRunReads(source),tx);
        var runCommands=new OwnerRunCreateService(new JdbcSourceRunStore(source),new JdbcSourceAdmission(source),new JdbcOwnerRunReceipts(source),writer,runReads,clock,
            id->{var gate=gates.getIfAvailable();return gate!=null&&gate.newRunAllowed(id);});
        return new SourceRegistryService(new JdbcSourceDirectory(source),reads,(actor,key,command)->{
            var receipts=new SourceCommandJdbcReceipts(new JdbcSourceCommandReceipts(source),reads,actor,key,command,clock);
            // These commands never call trial/promotion paths; production admission has its own host evidence provider.
            var unavailableEvidence=new SourceAdmissionService.EvidenceStore(){
                public SourceAdmissionPolicy.ProbeEvidence probe(String id,long version){return null;}
                public SourceAdmissionPolicy.PromotionEvidence promotion(String id,long version){return null;}
            };
            return new SourceRegistryService.BoundCommand(new SourceAdmissionService(new JdbcSourceAdmission(source),identity,writer,new SourceAdmissionPolicy(clock),unavailableEvidence,receipts,clock),receipts);
        },tx,runReads,runCommands);
    }
    @Bean @Order(5) SecurityFilterChain sourceReadsSecurity(HttpSecurity http) throws Exception {
        http.securityMatcher("/admin/sources","/admin/sources/*","/admin/sources/*/actions","/admin/source-runs","/admin/source-runs/*","/admin/source-runs/*/items")
                .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable).csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(r->r.requestMatchers(org.springframework.http.HttpMethod.GET,"/admin/source-runs","/admin/source-runs/*","/admin/source-runs/*/items").permitAll()
                    .requestMatchers(org.springframework.http.HttpMethod.POST,"/admin/source-runs").permitAll()
                    .requestMatchers("/admin/sources","/admin/sources/*","/admin/sources/*/actions").permitAll().anyRequest().denyAll());
        return http.build();
    }
}
