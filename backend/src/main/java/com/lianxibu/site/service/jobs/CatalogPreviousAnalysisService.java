package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Reads the original successful analysis. Source admission and version qualification are separate. */
public final class CatalogPreviousAnalysisService {
    public static final class Previous {
        private final String category,entity,content,root,inputHash,resultHash,inputJson,resultJson,identityJson,claimTargetHash,contentBodyHash;
        private final long generation,entityGeneration,entityRevision,contentRevision,contentBodyRevision;
        private final Instant appliedAt,inputCutoff;
        private Previous(String category,JdbcCatalogPreviousAnalysisOwnership.Entity entity,
                JdbcCatalogPreviousAnalysisOwnership.Content content,JdbcOwnerRetainedResultReads.Frame frame,
                ResultApplicationService.Validated result){
            identityJson=entity.identity();this.category=category;this.entity=entity.id();this.content=content.id();root=frame.root().id();
            inputHash=frame.inputHash();resultHash=frame.resultHash();inputJson=frame.inputJson();resultJson=result.payloadJson();
            generation=frame.generation();entityGeneration=entity.generation();entityRevision=entity.revision();
            contentRevision=content.revision();appliedAt=frame.appliedAt();inputCutoff=frame.root().cutoff();
            contentBodyRevision=content.bodyRevision();contentBodyHash=HexFormat.of().formatHex(content.bodyHash());var identity=frame.identity();
            var claim=tools.jackson.databind.json.JsonMapper.shared().createObjectNode().put("root_id",root).put("attempt_id",identity.attemptId()).put("dispatch_token",identity.fencing()).put("slot_revision",identity.slotRevision());
            claimTargetHash=JdbcResultState.sha(JdbcFrozenInputs.canonical(claim));
        }
        public String identityJson(){return identityJson;}public String category(){return category;}public String entityId(){return entity;}public String contentId(){return content;}
        public String rootId(){return root;}public String inputHash(){return inputHash;}public String resultHash(){return resultHash;}
        public String inputJson(){return inputJson;}public String resultJson(){return resultJson;}
        public long generation(){return generation;}public long entityGeneration(){return entityGeneration;}
        public long entityRevision(){return entityRevision;}public long contentRevision(){return contentRevision;}
        public Instant appliedAt(){return appliedAt;}public Instant inputCutoff(){return inputCutoff;}
        public String claimTargetHash(){return claimTargetHash;}public String contentBodyHash(){return contentBodyHash;}public long contentBodyRevision(){return contentBodyRevision;}
        @Override public String toString(){return "CatalogPreviousAnalysis[redacted]";}
    }
    private final JdbcCatalogPreviousAnalysisOwnership ownership;
    private final JdbcOwnerRetainedResultReads results;
    private final TransactionTemplate reads;
    public CatalogPreviousAnalysisService(DataSource source,PlatformTransactionManager transactions){
        ownership=new JdbcCatalogPreviousAnalysisOwnership(Objects.requireNonNull(source));
        results=new JdbcOwnerRetainedResultReads(source);reads=new TransactionTemplate(Objects.requireNonNull(transactions));
        reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    /** An absent original proof remains unknown; corrupt associations throw instead of returning a negative decision. */
    public Optional<Previous> read(String category,String entityId,String contentId){
        if(category==null||!Set.of("agent","skill","workflow","model").contains(category))throw invalid();
        WireValues.id(entityId,false);WireValues.id(contentId,false);
        if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();
        return reads.execute(status->capture(category,entityId,contentId));
    }
    /** Only an existing caller-owned readonly REPEATABLE_READ snapshot; no independent transaction is opened. */
    public Optional<Previous> readInSnapshot(String category,String entityId,String contentId){
        if(category==null||!Set.of("agent","skill","workflow","model").contains(category))throw invalid();
        WireValues.id(entityId,false);WireValues.id(contentId,false);
        if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                ||!Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();
        return capture(category,entityId,contentId);
    }
    private Optional<Previous> capture(String category,String entityId,String contentId){
        var entity=ownership.entity(entityId);var content=ownership.content(category,contentId);
        if(entity==null||content==null)return Optional.empty();
        if(!category.equals(entity.category())||!entityId.equals(content.entity()))throw invalid();
        if(entity.legacy()==null||entity.legacy()!=0||entity.identity()==null||entity.identityHash()==null
                ||entity.generation()==null||entity.state()==null||content.inputRoot()==null)return Optional.empty();
        if(!Arrays.equals(identityHash(category,entity.identity()),entity.identityHash())
                ||!safe(entity.revision(),false)||!safe(entity.generation(),true)
                ||!Set.of("queued","analyzed","failed").contains(entity.state())
                ||!safe(content.revision(),true)||!safe(content.bodyRevision(),true)
                ||content.bodyRevision()>content.revision()||content.bodyHash()==null||content.bodyHash().length!=32)throw invalid();
        if(content.state()!=null&&Set.of("blocked","withdrawn").contains(content.state()))return Optional.empty();
        if(content.state()==null||!Set.of("draft","published").contains(content.state()))throw invalid();
        var frame=results.read(content.inputRoot());
        if(frame==null||frame.resultHash()==null)return Optional.empty();
        var root=frame.root();
        if(!"succeeded".equals(root.state())||!"APPLIED".equals(root.applicationState())||frame.appliedAt()==null
                ||frame.identity()==null||!category.concat("_analysis").equals(root.taskType())
                ||frame.generation()>entity.generation()||frame.inputJson()==null||frame.envelope()==null)throw invalid();
        var snapshot=CanonicalJson.parse(frame.inputJson());
        if(!frame.inputHash().equals(CanonicalJson.hash(CanonicalJson.Domain.INPUT,snapshot)))throw invalid();
        var context=FrozenApplicationContext.fromJson(snapshot.get(FrozenApplicationContext.FIELD));
        if(!context.stage().analysis()||!category.equals(context.contentKind())||!contentId.equals(context.contentId())
                ||context.generation()!=frame.generation()||!context.referencesComplete()
                ||!context.stage().name().toLowerCase(Locale.ROOT).equals(WireValues.text(snapshot.get("stage")))
                ||frame.generation()!=WireValues.nonnegativeInteger(snapshot.get("generation")))throw invalid();
        var input=context.input(root.id(),frame.identity().attemptId(),frame.inputHash(),root.revision());
        if(!entityId.equals(input.targetEntityId()))throw invalid();
        var result=ResultApplicationService.validate(input,frame.envelope());
        if(!"analysis".equals(result.kind())||!frame.resultHash().equals(result.resultHash()))throw invalid();
        return Optional.of(new Previous(category,entity,content,frame,result));
    }
    private static byte[] identityHash(String category,String key){
        var value=tools.jackson.databind.json.JsonMapper.shared().createObjectNode().put("category",category).put("identity_key",key);
        return HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.IDENTITY,value));
    }
    private static boolean safe(Long value,boolean positive){return value!=null&&value>=(positive?1:0)&&value<=CanonicalJson.MAX_SAFE_INTEGER;}
    private static IllegalStateException invalid(){return new IllegalStateException("catalog previous analysis unavailable");}
}
