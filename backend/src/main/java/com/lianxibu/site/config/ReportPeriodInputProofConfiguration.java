package com.lianxibu.site.config;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.articles.JdbcEvidenceValidity;
import com.lianxibu.site.repository.content.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.articles.EvidenceValidityService;
import com.lianxibu.site.service.content.*;
import com.lianxibu.site.service.jobs.*;
import java.time.Clock;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;

/** Cold original period readers with actual immutable reference closure validity. No preparation executor. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"site.browser.enabled","site.content.enabled","site.articles.enabled","site.sources.enabled","site.execution.enabled","site.collector.enabled","site.sources.day-roster.enabled","site.execution.report-preparation.enabled"},havingValue="true")
public class ReportPeriodInputProofConfiguration {
    @Bean public ReportOriginalCompletionService reportOriginalCompletions(DataSource source,PlatformTransactionManager tx){return new ReportOriginalCompletionService(source,tx);}
    @Bean public ReportPeriodInputComponentService reportPeriodInputComponent(DataSource source,PlatformTransactionManager tx,ReportOriginalCompletionService completions,ReportNoUpdateProofService noUpdates){return assemble(source,tx,Clock.systemUTC(),completions,noUpdates);}
    public static ReportPeriodInputComponentService assemble(DataSource source,PlatformTransactionManager tx,Clock clock,ReportOriginalCompletionService completions){
        return assemble(source,tx,clock,completions,new ReportNoUpdateProofService(source,new ReportPreparedInputService(source,tx),new SharedWriteExecutor(new JdbcSharedWriteEntry(source),tx),tx,clock));
    }
    public static ReportPeriodInputComponentService assemble(DataSource source,PlatformTransactionManager tx,Clock clock,ReportOriginalCompletionService completions,ReportNoUpdateProofService noUpdates){
        Objects.requireNonNull(source);Objects.requireNonNull(tx);Objects.requireNonNull(clock);Objects.requireNonNull(completions);Objects.requireNonNull(noUpdates);
        var versions=new FrozenContentVersionService(new JdbcFrozenContentVersions(source));
        var writer=new ContentReviewWriteService(new JdbcReviewPublication(source),new GeneratedContentService(new JdbcGeneratedContents(source),clock),clock);
        var ledger=new VersionValidityLedgerService(new JdbcVersionValidity(source),versions,new EvidenceValidityService(new JdbcEvidenceValidity(source)),writer,clock,"report-period-reader");
        var summary=new ContentValiditySummaryService(versions,ledger);var current=new JdbcContentReads(source);
        ReportPeriodInputComponentService.VersionEligibility eligibility=version->{
            var validity=summary.read(version);var head=current.applicationHeader(version.contentKind(),version.contentId());
            if(head==null||!version.contentKind().equals(head.kind())||!version.contentId().equals(head.id())
                ||head.revision()<1||head.revision()>CanonicalJson.MAX_SAFE_INTEGER||head.bodyRevision()==null
                ||head.bodyRevision()<version.revision()||head.bodyRevision()>head.revision()
                ||head.bodyHash()==null||head.bodyHash().length!=32||head.inputJobId()==null||head.state()==null)throw unavailable();
            WireValues.id(head.inputJobId(),false);
            return new ReportPeriodInputComponentService.Validity(validity.effectiveValid(),!Set.of("draft","published").contains(head.state()));
        };
        return new ReportPeriodInputComponentService(source,completions,eligibility,noUpdates,tx);
    }
    private static IllegalStateException unavailable(){return new IllegalStateException("native report reference or content state unavailable");}
}
