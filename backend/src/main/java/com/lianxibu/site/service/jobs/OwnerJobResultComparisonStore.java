package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.content.ContentReadService;
import com.lianxibu.site.service.content.ContentService;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** One original native/input/result/content snapshot, without execution or application authority. */
public final class OwnerJobResultComparisonStore implements JobResultComparisonReadService.Store {
    private final JdbcOwnerRetainedResultReads original;
    private final OwnerJobInputMetadataService metadata;
    private final ContentReadService contents;
    private final ResultJdbcStore.ReferenceProof references;
    private final TransactionTemplate reads;
    public OwnerJobResultComparisonStore(DataSource source,PlatformTransactionManager tx,
            ContentReadService contents,ResultJdbcStore.ReferenceProof references){
        Objects.requireNonNull(source);original=new JdbcOwnerRetainedResultReads(source);
        metadata=new OwnerJobInputMetadataService(new JdbcOwnerJobViews(source),new JdbcFrozenInputs(source));
        this.contents=Objects.requireNonNull(contents);this.references=Objects.requireNonNull(references);
        reads=new TransactionTemplate(Objects.requireNonNull(tx));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    @Override public JobResultComparisonReadService.Snapshot snapshot(String jobId){return reads.execute(s->capture(jobId));}
    private JobResultComparisonReadService.Snapshot capture(String jobId){
        var frame=original.read(jobId);if(frame==null)return null;var root=frame.root();
        if(frame.resultHash()==null)return empty(root,null);
        var body=CanonicalJson.parse(frame.inputJson());
        if(!body.has(FrozenApplicationContext.FIELD))return empty(root,"unproven_input");
        var context=FrozenApplicationContext.fromJson(body.get(FrozenApplicationContext.FIELD));
        if(context.generation()!=frame.generation()||!context.stage().name().equalsIgnoreCase(root.taskType()))throw unavailable();
        if(context.stage().report()&&!body.has(com.lianxibu.site.service.content.ReportInputProfile.FIELD))return empty(root,"unproven_input");
        var meta=metadata.read(jobId);if(meta==null||!jobId.equals(meta.inputSnapshotId())||!Objects.equals(context.contentId(),meta.contentId()))throw unavailable();
        var input=context.input(jobId,frame.identity().attemptId(),frame.inputHash(),root.revision());
        var retained=ResultApplicationService.validate(input,frame.envelope());
        if(!frame.resultHash().equals(retained.resultHash()))throw unavailable();
        if(!context.stage().analysis()&&!context.stage().report())return new JobResultComparisonReadService.Snapshot(jobId,"logical_task",root.revision(),null,null,null,null,null,jobId,root.cutoff(),coverage(meta.coverage()),input,retained,null,"not_content_result");
        var content=contents.comparisonVersion(context.contentKind(),context.contentId()).orElse(null);
        boolean valid=context.referencesComplete()&&context.referenceTargets().stream().allMatch(references::valid);
        var current=new ResultApplicationService.Current(frame.latestGeneration(),frame.inputHash(),root.revision(),content==null?null:content.revision(),valid,
                "DISCARDED".equals(root.applicationState())||java.util.Set.of("discarded","cancelled").contains(root.state()),frame.appliedAt()==null?null:frame.resultHash(),frame.resultHash(),frame.identity().attemptId(),false,root.applicationState());
        return new JobResultComparisonReadService.Snapshot(jobId,"logical_task",root.revision(),context.contentId(),context.contentKind(),content==null?null:content.revision(),content==null?null:content.bodyRevision(),
                content==null||!content.proven()?null:CanonicalJson.parse(content.bodyJson()),jobId,root.cutoff(),coverage(meta.coverage()),input,retained,current,content!=null&&!content.proven()?"unproven_current_version":null);
    }
    private static JobResultComparisonReadService.Snapshot empty(JdbcOwnerJobViews.Row root,String reason){return new JobResultComparisonReadService.Snapshot(root.id(),root.kind(),root.revision(),null,null,null,null,null,null,null,null,null,null,null,reason);}
    private static ContentService.Coverage coverage(JobsService.Coverage value){return new ContentService.Coverage(value.state(),value.missingInputs().stream().map(v->new ContentService.MissingInput(v.kind(),v.id(),v.businessDate(),v.reason())).toList(),value.missingDates(),value.noUpdateDates());}
    private static IllegalStateException unavailable(){return new IllegalStateException("original result comparison proof unavailable");}
}
