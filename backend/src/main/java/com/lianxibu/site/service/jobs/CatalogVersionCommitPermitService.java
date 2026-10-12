package com.lianxibu.site.service.jobs;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.articles.CatalogVersionCurrentSourceService;
import com.lianxibu.site.service.articles.CatalogVersionQualificationService;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Original native version qualification, then fresh exact locks. Permanent decisions remain a separate business writer. */
public final class CatalogVersionCommitPermitService {
    public static final class Captured {
        private final Object issuer;private final CatalogVersionQualificationProjection.Projection projection;
        private final List<String> entity,content;
        private final JdbcCatalogVersionJobFacts.Snapshot jobs;
        private final CatalogVersionCurrentSourceService.Snapshot source;
        private Captured(Object issuer,CatalogVersionQualificationProjection.Projection projection,List<String> entity,List<String> content,JdbcCatalogVersionJobFacts.Snapshot jobs,CatalogVersionCurrentSourceService.Snapshot source){this.issuer=issuer;this.projection=projection;this.entity=entity;this.content=content;this.jobs=jobs;this.source=source;}
        public CatalogVersionQualificationProjection.Projection projection(){return projection;}
        public long entityRevision(){return Long.parseLong(entity.get(4));}
        public long entityGeneration(){return Long.parseLong(entity.get(5));}
        public String entityState(){return entity.get(6);}
        public java.time.LocalDate businessDate(){return java.time.LocalDate.parse(Objects.requireNonNull(source.scalars().get(18)));}
        @Override public String toString(){return "CapturedCatalogVersionCommit[redacted]";}
    }
    private final Object issuer=new Object();private final String category;
    private final JdbcCatalogVersionFrozenInputs frozenInputs;
    private final CatalogVersionQualificationService qualifications;
    private final JdbcCatalogPreviousAnalysisOwnership ownership;
    private final JdbcCatalogVersionJobFacts jobs;
    private final CatalogVersionCurrentSourceService sources;
    private final TransactionTemplate reads;
    public CatalogVersionCommitPermitService(DataSource source,PlatformTransactionManager manager,String category){
        Objects.requireNonNull(source);Objects.requireNonNull(manager);this.category=switch(Objects.requireNonNull(category)){case "agent","skill","workflow","model"->category;default->throw invalid();};
        frozenInputs=new JdbcCatalogVersionFrozenInputs(source,category);qualifications=new CatalogVersionQualificationService(source,manager);ownership=new JdbcCatalogPreviousAnalysisOwnership(source);jobs=new JdbcCatalogVersionJobFacts(source,category);sources=new CatalogVersionCurrentSourceService(source,category);
        reads=new TransactionTemplate(manager);reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public Optional<Captured> capture(String entityId,String contentId,String memberId,Instant cutoff){
        try{
            if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();return reads.execute(status->captureInSnapshot(entityId,contentId,memberId,cutoff));
        }catch(JdbcCatalogVersionJobFacts.CapacityRejected|CatalogVersionCurrentSourceService.CapacityRejected guarded){throw new GenerationFlowService.CapacityGuarded();}catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    /** Reads the actual original INPUT before binding fresh native state to its immutable proof. */
    public Captured captureFrozen(String rootId,String inputHash,String itemId){
        try{
            if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();
            return Objects.requireNonNull(reads.execute(status->{
                var stored=frozenInputs.read(rootId,inputHash);var node=com.lianxibu.site.domain.protocol.CanonicalJson.parse(stored.json());
                var input=FrozenApplicationContext.fromJson(node.get(FrozenApplicationContext.FIELD)).input(rootId,"catalog-version-read",inputHash,0);
                var original=FrozenCatalogVersionProjections.read(node.get(FrozenCatalogVersionProjections.FIELD),input).entries().get(itemId);
                if(original==null||!stored.cutoff().equals(original.cutoff()))throw invalid();
                var selection=qualifications.readInSnapshot(category,original.entityId(),original.contentId(),itemId,original.cutoff()).orElseThrow();
                var projection=CatalogVersionQualificationProjection.readOriginal(selection,original);
                return new Captured(issuer,projection,entity(ownership.entity(projection.entityId())),content(ownership.content(category,projection.contentId())),jobs.capture(projection.rootId(),projection.claimTargetHash()),sources.capture(itemId));
            }));
        }catch(JdbcCatalogVersionFrozenInputs.CapacityRejected|JdbcCatalogVersionJobFacts.CapacityRejected|CatalogVersionCurrentSourceService.CapacityRejected guarded){throw new GenerationFlowService.CapacityGuarded();}catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    /** Borrows only an actual readonly RR transaction, preserving one complete source capture point. */
    public Optional<Captured> captureInSnapshot(String entityId,String contentId,String memberId,Instant cutoff){
        try{
            if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                    ||!Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();
            var selection=qualifications.readInSnapshot(category,entityId,contentId,memberId,cutoff);if(selection.isEmpty())return Optional.empty();
            var projection=CatalogVersionQualificationProjection.read(selection.orElseThrow());var entity=entity(ownership.entity(entityId));var content=content(ownership.content(category,contentId));
            var nativeJobs=jobs.capture(projection.rootId(),projection.claimTargetHash());var nativeSource=sources.capture(memberId);
            return Optional.of(new Captured(issuer,projection,entity,content,nativeJobs,nativeSource));
        }catch(JdbcCatalogVersionJobFacts.CapacityRejected|CatalogVersionCurrentSourceService.CapacityRejected guarded){throw new GenerationFlowService.CapacityGuarded();}catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    /** Recheck before any business mutation; keep all native locks until the original caller's writer commits. */
    public CatalogVersionQualificationProjection.Projection verify(Captured captured){return verifyAll(List.of(Objects.requireNonNull(captured))).get(captured.projection.memberId());}
    private record Original(String entity,String content,String root){ }
    /** Lock each common original proof once and every exact current leaf/config in one bounded query. */
    public Map<String,CatalogVersionQualificationProjection.Projection> verifyAll(Collection<Captured> captured){
        try{
            JdbcFrozenInputs.requireTx();if(!Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();Objects.requireNonNull(captured);if(captured.size()>100)throw new GenerationFlowService.CapacityGuarded();var members=new TreeMap<String,Captured>();var originals=new HashMap<Original,Captured>();
            for(var c:captured){if(c==null||c.issuer!=issuer||!category.equals(c.projection.category())||members.putIfAbsent(c.projection.memberId(),c)!=null)throw invalid();var p=c.projection;var key=new Original(p.entityId(),p.contentId(),p.rootId());var prior=originals.putIfAbsent(key,c);if(prior!=null&&(!prior.entity.equals(c.entity)||!prior.content.equals(c.content)||!prior.jobs.equals(c.jobs)))throw invalid();}
            for(var group:originals.entrySet().stream().sorted(Comparator.comparing((Map.Entry<Original,Captured> e)->e.getKey().entity()).thenComparing(e->e.getKey().content()).thenComparing(e->e.getKey().root())).toList()){var c=group.getValue();var p=c.projection;if(!c.entity.equals(entity(ownership.lockedEntity(p.entityId())))||!c.content.equals(content(ownership.lockedContent(category,p.contentId())))||!c.jobs.equals(jobs.lockAndRead(p.rootId(),p.claimTargetHash())))throw invalid();}
            var nativeSources=sources.lockAndReadAll(members.keySet());if(!nativeSources.keySet().equals(members.keySet()))throw invalid();var projections=new HashMap<String,CatalogVersionQualificationProjection.Projection>();members.forEach((id,c)->{if(!c.source.equals(nativeSources.get(id)))throw invalid();projections.put(id,c.projection);});return Map.copyOf(projections);
        }catch(JdbcCatalogVersionJobFacts.CapacityRejected|CatalogVersionCurrentSourceService.CapacityRejected guarded){throw new GenerationFlowService.CapacityGuarded();}catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    private static List<String> entity(JdbcCatalogPreviousAnalysisOwnership.Entity e){if(e==null)throw invalid();return values(e.id(),e.category(),e.identity(),hex(e.identityHash()),e.revision(),e.generation(),e.state(),e.legacy(),e.identityBytes());}
    private static List<String> content(JdbcCatalogPreviousAnalysisOwnership.Content c){if(c==null)throw invalid();return values(c.kind(),c.id(),c.entity(),c.inputRoot(),hex(c.bodyHash()),c.revision(),c.bodyRevision(),c.state());}
    private static List<String> values(Object... raw){var values=new ArrayList<String>();for(var value:raw)values.add(value==null?null:value.toString());return Collections.unmodifiableList(values);}
    private static String hex(byte[] bytes){return bytes==null?null:HexFormat.of().formatHex(bytes);}
    private static IllegalStateException invalid(){return new IllegalStateException("original catalog version writer permission unavailable");}
}
