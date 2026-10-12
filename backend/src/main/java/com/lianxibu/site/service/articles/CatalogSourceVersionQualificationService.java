package com.lianxibu.site.service.articles;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.domain.sources.MemberUniverse;
import com.lianxibu.site.repository.articles.JdbcSourceMemberEvidence;
import com.lianxibu.site.service.jobs.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** One actual read snapshot captures all known original version comparisons. It grants no writer permission. */
public final class CatalogSourceVersionQualificationService {
    private final String category;
    private final CatalogCurrentAnalysisContentService contents;
    private final CatalogVersionQualificationService versions;
    private final JdbcSourceMemberEvidence sourceFacts;
    private final SourceMemberEvidenceService sourceEvidence;
    private final TransactionTemplate reads;
    public CatalogSourceVersionQualificationService(DataSource source,PlatformTransactionManager manager,String category){
        Objects.requireNonNull(source);Objects.requireNonNull(manager);this.category=switch(Objects.requireNonNull(category)){case "agent","skill","workflow","model"->category;default->throw invalid();};
        contents=new CatalogCurrentAnalysisContentService(source,manager,category);versions=new CatalogVersionQualificationService(source,manager);
        sourceFacts=new JdbcSourceMemberEvidence(source);sourceEvidence=new SourceMemberEvidenceService(sourceFacts);
        reads=new TransactionTemplate(manager);reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public Map<String,CatalogVersionQualificationProjection.Projection> capture(CatalogDedupEligibilityService.Selection selection,NewsCandidateRecall.CategoryHistory history){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();return Objects.requireNonNull(reads.execute(s->captureInSnapshot(selection,history)));
    }
    public Map<String,CatalogVersionQualificationProjection.Projection> captureInSnapshot(CatalogDedupEligibilityService.Selection selection,NewsCandidateRecall.CategoryHistory history){
        try{
            if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                    ||!Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();
            Objects.requireNonNull(selection);Objects.requireNonNull(history);if(!category.equals(selection.category())||!category.equals(history.category())||!selection.cutoff().equals(history.facts().cutoff()))throw invalid();
            if(selection.entries().size()>MemberUniverse.MAX_MEMBERS)throw new GenerationFlowService.CapacityGuarded();
            var entities=new HashMap<String,NewsCandidateRecall.Entity>();var entityIds=new HashSet<String>();
            for(var entity:history.facts().entities())if(entities.put(entity.identityKey(),entity)!=null||!entityIds.add(entity.id()))throw invalid();
            var candidates=new TreeMap<String,NewsCandidateRecall.Entity>();var facts=new HashMap<String,SourceMemberEvidenceService.Fact>();var seen=new HashSet<String>();
            for(var entry:selection.entries()){
                if(entry==null||!seen.add(entry.itemId()))throw invalid();if(entry.reason()!=CatalogDedupEligibilityService.Reason.ELIGIBLE)continue;
                var fact=entry.fact();if(!category.equals(fact.category())||!selection.day().equals(entry.businessDate())||entry.completedAt().isAfter(selection.cutoff()))throw invalid();
                if(!entry.itemId().equals(fact.id())||facts.put(entry.itemId(),fact)!=null)throw invalid();
                var entity=entities.get(fact.identityKey());if(entity==null||entity.legacy())continue;candidates.put(entry.itemId(),entity);
            }
            // An unknown old version cannot conceal a stale current native observation.
            var factIds=new ArrayList<>(new TreeSet<>(facts.keySet()));
            for(int offset=0;offset<factIds.size();offset+=100){
                var batch=Set.copyOf(factIds.subList(offset,Math.min(offset+100,factIds.size())));
                var rows=new HashMap<String,JdbcSourceMemberEvidence.Row>();var ordered=new ArrayList<>(batch);
                for(int n=0;n<ordered.size();n+=16)rows.putAll(sourceFacts.observedBatch(Set.copyOf(ordered.subList(n,Math.min(n+16,ordered.size())))));
                var observed=sourceEvidence.readObserved(batch,selection.cutoff());
                if(rows.size()!=batch.size()||observed.size()!=batch.size())throw invalid();
                for(var actual:observed){var row=rows.get(actual.id());if(!actual.equals(facts.get(actual.id()))||row==null
                        ||!selection.runId().equals(row.runId())||!selection.sourceId().equals(row.sourceId()))throw invalid();}
            }
            var unique=new TreeSet<String>();candidates.values().forEach(entity->unique.add(entity.id()));var ids=new ArrayList<>(unique);var currentContents=new HashMap<String,String>();
            for(int offset=0;offset<ids.size();offset+=100)currentContents.putAll(contents.findInSnapshot(Set.copyOf(ids.subList(offset,Math.min(offset+100,ids.size())))));
            var qualified=new HashMap<String,CatalogVersionQualificationProjection.Projection>();long bytes=0;
            for(var entry:candidates.entrySet()){
                var entity=entry.getValue();String content=currentContents.get(entity.id());if(content==null)continue;
                var actual=versions.readInSnapshot(category,entity.id(),content,entry.getKey(),selection.cutoff());if(actual.isEmpty())continue;
                var p=CatalogVersionQualificationProjection.read(actual.orElseThrow());var fact=facts.get(entry.getKey());var proof=p.toJson();var current=proof.get("current_proof");
                if(entity.revision()!=WireValues.nonnegativeInteger(proof.get("entity_revision"))||!selection.runId().equals(current.get("run_id").asText())
                        ||!selection.sourceId().equals(current.get("source_id").asText())||fact.revision()!=WireValues.nonnegativeInteger(current.get("member_revision"))
                        ||!CanonicalJson.hash(CanonicalJson.Domain.CONTENT,CanonicalJson.parse(fact.payloadJson())).equals(current.get("body_hash").asText())
                        ||!fact.identityKey().equals(canonical(proof.get("current_marker").get("identity"))))throw invalid();
                bytes+=CanonicalJson.bytes(proof).length;if(bytes>32L*1024*1024)throw new GenerationFlowService.CapacityGuarded();qualified.put(entry.getKey(),p);
            }
            return Map.copyOf(qualified);
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(CatalogCurrentAnalysisContentService.CapacityRejected guarded){throw new GenerationFlowService.CapacityGuarded();}catch(RuntimeException rejected){throw invalid();}
    }
    private static String canonical(tools.jackson.databind.JsonNode value){return new String(CanonicalJson.bytes(value),java.nio.charset.StandardCharsets.UTF_8);}
    private static IllegalStateException invalid(){return new IllegalStateException("native catalog source version qualification unavailable");}
}
