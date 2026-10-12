package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.content.ReportInputProfile;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Actual original completed report at a cutoff. No current contents body is queried. */
public final class ReportOriginalCompletionService {
    public record Completed(JdbcReportTaskHeaders.Header header,JdbcReportCompletionReceipts.Ready ready,
                            String bodyJson,String snapshotJson,Instant availableAt,long originalByteSize) {
        public FrozenApplicationContext context(){return FrozenApplicationContext.fromJson(CanonicalJson.parse(snapshotJson).get(FrozenApplicationContext.FIELD));}
        @Override public String toString(){return "OriginalCompletedReport[redacted]";}
    }
    public static final long MAX_ORIGINAL_BYTES=32L*1024*1024;
    private final JdbcReportTaskHeaders headers;
    private final JdbcReportCompletionReceipts receipts;
    private final JdbcFrozenInputs inputs;
    private final JdbcResultState results;
    private final JdbcCatalogAnalysisResultVersions appliedVersions;
    private final JdbcVersionAvailability first;
    private final TransactionTemplate reads;
    private final long maximum;
    public ReportOriginalCompletionService(DataSource source,PlatformTransactionManager manager){this(source,manager,MAX_ORIGINAL_BYTES);}
    public ReportOriginalCompletionService(DataSource source,PlatformTransactionManager manager,long maximum){
        if(maximum<1024||maximum>MAX_ORIGINAL_BYTES)throw invalid();this.maximum=maximum;
        Objects.requireNonNull(source);headers=new JdbcReportTaskHeaders(source);receipts=new JdbcReportCompletionReceipts(source);
        inputs=new JdbcFrozenInputs(source);results=new JdbcResultState(source);appliedVersions=new JdbcCatalogAnalysisResultVersions(source);
        first=new JdbcVersionAvailability(source);reads=new TransactionTemplate(Objects.requireNonNull(manager));
        reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public Completed find(String root,Instant cutoff){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();
        return reads.execute(s->read(root,cutoff));
    }
    public Completed read(String root,Instant cutoff){
        readonly();WireValues.id(root,false);if(cutoff==null||cutoff.getNano()%1000!=0)throw invalid();
        var ready=receipts.read(root);if(ready==null)return null;
        var header=headers.read(root);if(header==null||!"succeeded".equals(header.state())||!"APPLIED".equals(header.application())
            ||header.calls()<1||!"ready".equals(header.inputState())||header.appliedAt()==null||header.cutoff()==null
            ||!ready.stage().equals(header.stage())||ready.generation()!=header.generation()
            ||!ready.inputHash().equals(header.inputHash())||!ready.resultHash().equals(header.resultHash())
            ||!header.appliedAt().equals(ready.recordedAt())||header.cutoff().isAfter(header.appliedAt()))throw invalid();
        headers.guardOriginalInput(root);
        String snapshot=inputs.read(root,ready.inputHash());var original=CanonicalJson.parse(snapshot);ReportInputProfile.validated(original);
        var profile=original.get(ReportInputProfile.FIELD);
        var context=FrozenApplicationContext.fromJson(original.get(FrozenApplicationContext.FIELD));
        if(!header.logicalKey().equals(WireValues.text(original.get("logical_key")))
            ||header.generation()!=WireValues.nonnegativeInteger(original.get("generation"))
            ||!header.stage().equals(WireValues.text(original.get("stage")))||!header.day().equals(WireValues.businessDate(original.get("business_date")))
            ||!header.cutoff().equals(Instant.parse(WireValues.text(original.get("cutoff"))))
            ||!context.referencesComplete()||context.generation()!=header.generation()||!"report".equals(context.contentKind())
            ||!ready.version().contentId().equals(context.contentId())||!ready.stage().equals(context.input(root,"completion-read",ready.inputHash(),0).stage().name().toLowerCase(Locale.ROOT))
            ||!ready.periodStart().equals(WireValues.utcMicroseconds(profile.get("period_start")))
            ||!ready.periodEnd().equals(WireValues.utcMicroseconds(profile.get("period_end")))
            ||!ready.periodStart().equals(WireValues.utcMicroseconds(context.toJson().get("period_start")))
            ||!ready.periodEnd().equals(WireValues.utcMicroseconds(context.toJson().get("period_end"))))throw invalid();
        var versions=appliedVersions.read(root,ready.inputHash(),ready.resultHash());
        if(versions.size()!=1||!ready.version().equals(versions.getFirst()))throw invalid();
        var retained=results.root(root);
        if(!header.id().equals(retained.id())||!header.inputHash().equals(retained.hash())||header.revision()!=retained.revision()
            ||!header.state().equals(retained.state())||!header.application().equals(retained.application())
            ||!header.resultHash().equals(retained.resultHash()))throw invalid();
        // The native report header supplies UTC DATETIME facts; the legacy root's latest generation and JVM-local time do not.
        var originalRoot=new JdbcResultState.Root(header.id(),header.inputHash(),header.revision(),header.generation(),header.state(),
            header.application(),header.resultHash(),retained.resultJson(),header.appliedAt());
        String envelope=results.retained(originalRoot);if(envelope==null)throw invalid();
        var result=ResultApplicationService.validate(context.input(root,"completion-read",ready.inputHash(),0),envelope);
        var body=CanonicalJson.parse(result.payloadJson()).get("body");
        if(!"report".equals(result.kind())||!ready.resultHash().equals(result.resultHash())
            ||!ready.version().bodyHash().equals(CanonicalJson.hash(CanonicalJson.Domain.CONTENT,body)))throw invalid();
        String rawBody=JdbcFrozenInputs.canonical(body);
        long bytes=(long)snapshot.getBytes(StandardCharsets.UTF_8).length+envelope.getBytes(StandardCharsets.UTF_8).length
            +ready.payloadJson().getBytes(StandardCharsets.UTF_8).length+rawBody.getBytes(StandardCharsets.UTF_8).length
            +JdbcFrozenContentVersions.version(ready.version()).getBytes(StandardCharsets.UTF_8).length;
        if(bytes>maximum)throw invalid();
        Instant observed=first.read(ready.version(),ready.inputHash());if(observed==null)return null;
        if(observed.isBefore(ready.recordedAt())||observed.isBefore(header.createdAt()))throw invalid();
        if(observed.isAfter(cutoff))return null;
        return new Completed(header,ready,rawBody,snapshot,observed,bytes);
    }
    private static void readonly(){if(!TransactionSynchronizationManager.isActualTransactionActive()
        ||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        ||!Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("original completed report unavailable");}
    @Override public String toString(){return "ReportOriginalCompletionService[redacted]";}
}
