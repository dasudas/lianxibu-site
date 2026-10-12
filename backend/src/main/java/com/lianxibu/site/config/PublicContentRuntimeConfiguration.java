package com.lianxibu.site.config;

import com.lianxibu.site.repository.articles.JdbcEvidenceValidity;
import com.lianxibu.site.repository.content.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.articles.EvidenceValidityService;
import com.lianxibu.site.service.content.*;
import com.lianxibu.site.service.jobs.*;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.transaction.PlatformTransactionManager;

/** Qualified public reads and authenticated owner review over permanent original proofs. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"site.browser.enabled","site.content.enabled"},havingValue="true")
public class PublicContentRuntimeConfiguration {
    @Bean ContentService publicContent(DataSource source,PlatformTransactionManager tx,ObjectProvider<OwnerReportOperations> reports) {
        var clock=Clock.systemUTC();String reader="public-reader";
        var versions=new FrozenContentVersionService(new JdbcFrozenContentVersions(source));
        var unqualified=new ContentReadService(new JdbcContentReads(source),tx,ContentReadService.denyUnverifiedReferences());
        var writer=new ContentReviewWriteService(new JdbcReviewPublication(source),new GeneratedContentService(new JdbcGeneratedContents(source),clock),clock);
        var ledger=new VersionValidityLedgerService(new JdbcVersionValidity(source),versions,new EvidenceValidityService(new JdbcEvidenceValidity(source)),writer,clock,reader);
        var audit=new ContentReviewEvidenceService(source,unqualified,versions,clock,reader);
        var writes=new SharedWriteExecutor(new JdbcSharedWriteEntry(source),tx);
        var reviewReads=new ReviewPublicationJdbcStore(null,writer,unqualified,audit,versions,ledger);
        var qualified=new ContentReadService(new JdbcContentReads(source),tx,new ReviewPublicationService(writes,reviewReads));
        var projection=new FrozenPublishedProjection(versions,new ContentValiditySummaryService(versions,ledger),new GenerationLineageService(new JdbcGenerationLineage(source),clock,reader));
        var ownerReads=new OwnerContentReadService(unqualified,projection,reviewReads,tx);
        var commands=new OwnerContentCommands((actor,command)->{
            var ownerLedger=new VersionValidityLedgerService(new JdbcVersionValidity(source),versions,new EvidenceValidityService(new JdbcEvidenceValidity(source)),writer,clock,actor);
            var effects=new ContentWithdrawalEffects(ownerLedger,new JdbcValidityPropagation(source),writer,clock);
            return new ReviewPublicationService(writes,new ReviewPublicationJdbcStore(command,writer,unqualified,
                new ContentReviewEvidenceService(source,unqualified,versions,clock,actor),versions,ownerLedger,effects));
        });
        var edits=new OwnerContentEditService((actor,command,key)->{
            var store=new ReviewPublicationJdbcStore(command,writer,unqualified,new ContentReviewEvidenceService(source,unqualified,versions,clock,actor),versions,ledger);
            return new OwnerContentEditService.Operation(new ReviewPublicationService(writes,store),()->store.recordedEdit(key));
        },projection,tx);
        var validityCommands=new OwnerContentValidityService(actor->{
            var ownerLedger=new VersionValidityLedgerService(new JdbcVersionValidity(source),versions,new EvidenceValidityService(new JdbcEvidenceValidity(source)),writer,clock,actor);
            return new VersionValidityService(writes,new VersionValidityJdbcStore(ownerLedger,new JdbcValidityPropagation(source),versions,unqualified,writer,clock,actor,null));
        });
        var catalog=new ContentVersionCatalog(new JdbcValidityPropagation(source),versions,ledger,unqualified,tx);
        return new PublishedContentFacade(qualified,projection,tx,new PublishedContentFacade.OwnerOperations(ownerReads,commands,edits,validityCommands,catalog::list),reports.getIfAvailable());
    }
    @Bean VersionDispositionReadService versionDispositions(DataSource source,PlatformTransactionManager tx){
        var clock=Clock.systemUTC();String reader="version-reader";
        var versions=new FrozenContentVersionService(new JdbcFrozenContentVersions(source));
        var contents=new ContentReadService(new JdbcContentReads(source),tx,ContentReadService.denyUnverifiedReferences());
        var writer=new ContentReviewWriteService(new JdbcReviewPublication(source),new GeneratedContentService(new JdbcGeneratedContents(source),clock),clock);
        var ledger=new VersionValidityLedgerService(new JdbcVersionValidity(source),versions,new EvidenceValidityService(new JdbcEvidenceValidity(source)),writer,clock,reader);
        var proof=new VersionValidityJdbcStore(ledger,new JdbcValidityPropagation(source),versions,contents,writer,clock,reader,null);
        return new VersionDispositionReadService(new JdbcVersionDispositionReads(source),proof,tx);
    }
    @Bean @Order(6) SecurityFilterChain publicContentSecurity(HttpSecurity http) throws Exception {
        http.securityMatcher("/reports","/reports/*","/interpretations","/interpretations/*")
            .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(AbstractHttpConfigurer::disable).csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(r->r.requestMatchers(HttpMethod.GET,"/reports","/reports/*","/interpretations","/interpretations/*").permitAll().anyRequest().denyAll());
        return http.build();
    }
    @Bean @Order(7) SecurityFilterChain ownerContentSecurity(HttpSecurity http) throws Exception {
        http.securityMatcher("/admin/content/*/*","/admin/content/*/*/actions","/admin/content/*/*/version-actions","/admin/evidence/*/*/actions","/admin/content/*/*/referenced-versions","/admin/content/*/*/version-dispositions","/admin/reports/generation-intents")
            .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(AbstractHttpConfigurer::disable).csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(r->r.requestMatchers(HttpMethod.GET,"/admin/content/*/*","/admin/content/*/*/referenced-versions","/admin/content/*/*/version-dispositions","/admin/reports/generation-intents").permitAll()
                .requestMatchers(HttpMethod.PUT,"/admin/content/*/*").permitAll()
                .requestMatchers(HttpMethod.POST,"/admin/content/*/*/actions","/admin/content/*/*/version-actions","/admin/evidence/*/*/actions","/admin/reports/generation-intents").permitAll().anyRequest().denyAll());
        return http.build();
    }
}
