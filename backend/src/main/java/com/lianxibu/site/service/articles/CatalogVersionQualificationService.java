package com.lianxibu.site.service.articles;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.articles.JdbcSourceMemberEvidence;
import com.lianxibu.site.service.jobs.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import tools.jackson.databind.json.JsonMapper;

/** Native read preflight only. Applying a version judgment still requires a fresh original writer proof. */
public final class CatalogVersionQualificationService {
    public static final class Selection {
        private final CatalogPreviousVersionProfile.Profile previous;
        private final CatalogVersionEvidenceMarker.Marker current;
        private final SourceMemberEvidenceService.Fact member;
        private final boolean changed;
        private final Instant cutoff;
        private final String currentProofJson,currentProofHash,qualificationHash;
        private Selection(CatalogPreviousVersionProfile.Profile previous,CatalogVersionEvidenceMarker.Marker current,
                SourceMemberEvidenceService.Fact member,JdbcSourceMemberEvidence.Row row,boolean changed,Instant cutoff){
            this.previous=previous;this.current=current;this.member=member;this.changed=changed;this.cutoff=cutoff;
            var original=previous.evidence().previous();String configHash=CanonicalJson.hash(CanonicalJson.Domain.INPUT,CanonicalJson.parse(row.config()));
            var definition=JsonMapper.shared().createObjectNode().put("schema_version","catalog-version-qualification-v1").put("category",original.category()).put("source_id",row.sourceId()).put("config_hash",configHash);
            qualificationHash=CanonicalJson.hash(CanonicalJson.Domain.INPUT,definition);
            var proof=JsonMapper.shared().createObjectNode().put("schema_version","catalog-current-version-proof-v1").put("category",original.category()).put("entity_id",original.entityId()).put("entity_revision",original.entityRevision()).put("entity_generation",original.entityGeneration()).put("source_id",row.sourceId()).put("run_id",row.runId()).put("item_id",member.id()).put("member_revision",member.revision()).put("config_version",row.configVersion()).put("config_hash",configHash).put("body_hash",CanonicalJson.hash(CanonicalJson.Domain.CONTENT,CanonicalJson.parse(member.payloadJson()))).put("version_hash",current.hash()).put("qualification_hash",qualificationHash);
            currentProofJson=new String(CanonicalJson.bytes(proof),StandardCharsets.UTF_8);if(currentProofJson.getBytes(StandardCharsets.UTF_8).length>16384)throw invalid();currentProofHash=CanonicalJson.hash(CanonicalJson.Domain.INPUT,proof);
        }
        public CatalogPreviousVersionProfile.Profile previous(){return previous;}public CatalogVersionEvidenceMarker.Marker current(){return current;}
        public SourceMemberEvidenceService.Fact member(){return member;}public boolean versionChanged(){return changed;}public Instant cutoff(){return cutoff;}
        public String currentProofJson(){return currentProofJson;}public String currentProofHash(){return currentProofHash;}public String qualificationHash(){return qualificationHash;}
        @Override public String toString(){return "CatalogVersionNativePreflight[redacted]";}
    }
    private final CatalogPreviousAnalysisService previous;
    private final JdbcSourceMemberEvidence sources;
    private final SourceMemberEvidenceService evidence;
    private final TransactionTemplate reads;
    public CatalogVersionQualificationService(DataSource source,PlatformTransactionManager manager){
        Objects.requireNonNull(source);Objects.requireNonNull(manager);previous=new CatalogPreviousAnalysisService(source,manager);
        sources=new JdbcSourceMemberEvidence(source);evidence=new SourceMemberEvidenceService(sources);reads=new TransactionTemplate(manager);
        reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public Optional<Selection> read(String category,String entityId,String contentId,String memberId,Instant cutoff){
        if(category==null||!Set.of("agent","skill","workflow","model").contains(category)||cutoff==null||cutoff.getNano()%1000!=0)throw invalid();
        WireValues.id(entityId,false);WireValues.id(contentId,false);WireValues.id(memberId,false);
        if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();
        return reads.execute(status->capture(category,entityId,contentId,memberId,cutoff));
    }
    /** Shares only the caller's existing readonly REPEATABLE_READ snapshot, never opens a second transaction. */
    public Optional<Selection> readInSnapshot(String category,String entityId,String contentId,String memberId,Instant cutoff){
        if(category==null||!Set.of("agent","skill","workflow","model").contains(category)||cutoff==null||cutoff.getNano()%1000!=0)throw invalid();
        WireValues.id(entityId,false);WireValues.id(contentId,false);WireValues.id(memberId,false);
        if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();
        return capture(category,entityId,contentId,memberId,cutoff);
    }
    /** Releases the full old INPUT/result and current payload after constructing one bounded original proof projection. */
    public Optional<CatalogVersionQualificationProjection.Projection> readProjection(String category,String entityId,String contentId,String memberId,Instant cutoff){
        return read(category,entityId,contentId,memberId,cutoff).map(CatalogVersionQualificationProjection::read);
    }
    private Optional<Selection> capture(String category,String entityId,String contentId,String memberId,Instant cutoff){
        var original=previous.readInSnapshot(category,entityId,contentId);if(original.isEmpty())return Optional.empty();
        if(original.orElseThrow().appliedAt().isAfter(cutoff))throw invalid();
        var oldEvidence=CatalogPreviousAnalysisEvidence.read(original.orElseThrow());if(oldEvidence.isEmpty())return Optional.empty();
        var profile=CatalogPreviousVersionProfile.read(oldEvidence.orElseThrow());if(profile.isEmpty())return Optional.empty();
        var row=sources.observed(memberId);if(row==null)return Optional.empty();
        if(row.configVersion()<1)throw invalid();
        var facts=evidence.readObserved(Set.of(memberId),cutoff);if(facts.size()!=1)throw invalid();var fact=facts.getFirst();
        if(!memberId.equals(fact.id())||!category.equals(fact.category())||!original.orElseThrow().identityJson().equals(fact.identityKey()))throw invalid();
        var current=CatalogVersionEvidenceMarker.readCatalog(CanonicalJson.parse(fact.payloadJson()));if(current.isEmpty())return Optional.empty();
        var changed=profile.orElseThrow().changed(current.orElseThrow());if(changed.isEmpty())return Optional.empty();
        return Optional.of(new Selection(profile.orElseThrow(),current.orElseThrow(),fact,row,changed.orElseThrow(),cutoff));
    }
    private static IllegalStateException invalid(){return new IllegalStateException("catalog native version qualification unavailable");}
}
