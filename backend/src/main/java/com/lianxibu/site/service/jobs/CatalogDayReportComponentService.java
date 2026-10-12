package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.ReportInputSnapshotService.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import tools.jackson.databind.JsonNode;

/** One original category's complete report candidate set. Does not prove other report groups or grant a writer. */
public final class CatalogDayReportComponentService {
    public record DirectoryRef(String ownerId,String bundleHash,Instant sealedAt){
        public DirectoryRef{WireValues.id(ownerId,false);if(bundleHash==null||!bundleHash.matches("[0-9a-f]{64}")||sealedAt==null||sealedAt.getNano()%1000!=0)throw invalid();}
        @Override public String toString(){return "OriginalCatalogDirectoryRef[redacted]";}
    }
    public record Validity(boolean effectiveValid,boolean blocked){ }
    @FunctionalInterface public interface VersionEligibility{Validity read(ContentVersionKey version);}
    private record Selected(List<Candidate> candidates,List<GenerationFlowService.Dependency> dependencies,List<String> referenceJson,List<MissingInput> missing){
        private Selected{candidates=List.copyOf(candidates);dependencies=List.copyOf(dependencies);referenceJson=List.copyOf(referenceJson);missing=List.copyOf(missing);}
    }
    public static final class Captured {
        private final Object issuer;private final CatalogDayEntityEvidenceService.Captured original;private final Instant cutoff;private final Selected selected;
        private Captured(Object issuer,CatalogDayEntityEvidenceService.Captured original,Instant cutoff,Selected selected){this.issuer=issuer;this.original=original;this.cutoff=cutoff;this.selected=selected;}
        public DirectoryRef directory(){var sealed=original.original().closed().original();return new DirectoryRef(sealed.plan().directory().ownerId(),sealed.plan().bundleHash(),sealed.sealedAt());}
        public String category(){return original.original().category();}public LocalDate day(){return original.original().day();}public Instant cutoff(){return cutoff;}
        public long originalByteSize(){long bytes=original.originalByteSize();for(var candidate:selected.candidates())if(candidate.evidence()!=null)bytes=Math.addExact(bytes,candidate.evidence().bodyJson().getBytes(StandardCharsets.UTF_8).length);for(String target:selected.referenceJson())bytes=Math.addExact(bytes,target.getBytes(StandardCharsets.UTF_8).length);return bytes;}
        public List<Candidate> candidates(){return selected.candidates();}public List<GenerationFlowService.Dependency> dependencies(){return selected.dependencies();}
        public List<String> referenceJson(){return selected.referenceJson();}public List<MissingInput> missing(){return selected.missing();}
        @Override public String toString(){return "OriginalCatalogReportComponent[redacted]";}
    }
    private final Object issuer=new Object();private final String category;
    private final CatalogDayEntityEvidenceService evidence;private final FrozenSourceStagePlanService plans;private final CatalogVersionJudgmentLedger judgments;
    private final CatalogDayAnalysisReservationLedger reservations;private final CatalogDayAnalysisCompletionService completions;private final VersionEligibility eligibility;private final TransactionTemplate reads;
    public CatalogDayReportComponentService(CatalogDayEntityEvidenceService evidence,FrozenSourceStagePlanService plans,CatalogVersionJudgmentLedger judgments,
            CatalogDayAnalysisReservationLedger reservations,CatalogDayAnalysisCompletionService completions,VersionEligibility eligibility,PlatformTransactionManager manager,String category){
        this.category=switch(Objects.requireNonNull(category)){case "agent","skill","workflow","model"->category;default->throw invalid();};
        this.evidence=Objects.requireNonNull(evidence);this.plans=Objects.requireNonNull(plans);this.judgments=Objects.requireNonNull(judgments);this.reservations=Objects.requireNonNull(reservations);this.completions=Objects.requireNonNull(completions);this.eligibility=Objects.requireNonNull(eligibility);
        reads=new TransactionTemplate(Objects.requireNonNull(manager));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public String category(){return category;}
    public Captured capture(LocalDate day,Instant cutoff){
        if(TransactionSynchronizationManager.isActualTransactionActive()||day==null||cutoff==null||cutoff.getNano()%1000!=0||cutoff.isBefore(day.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant()))throw invalid();
        var original=evidence.captureOriginal(day);if(original==null)return null;
        return reads.execute(s->{evidence.verifyObserved(original);return new Captured(issuer,original,cutoff,select(original,cutoff));});
    }
    /** Recheck the complete component in the report provider's read-only RR snapshot before freezing it. */
    public Captured verifyObserved(Captured captured){
        readonly();if(captured==null||captured.issuer!=issuer||!category.equals(captured.category()))throw invalid();evidence.verifyObserved(captured.original);
        if(!captured.selected.equals(select(captured.original,captured.cutoff)))throw invalid();return captured;
    }
    private Selected select(CatalogDayEntityEvidenceService.Captured original,Instant cutoff){
        readonly();if(!category.equals(original.original().category())||original.original().cutoff().isAfter(cutoff))throw invalid();
        var contexts=new HashMap<String,JsonNode>();var decisions=new HashMap<String,String>();
        for(var source:original.original().sources()){
            var body=CanonicalJson.parse(source.result().json());var reference=FrozenSourceStagePlan.Reference.fromJson(JdbcFrozenInputs.canonical(body.get("plan_ref")));var plan=plans.readImmutable(reference);
            for(var chunk:plan.chunks())if(contexts.putIfAbsent(chunk.rootId(),CanonicalJson.parse(chunk.snapshotJson()))!=null)throw invalid();
            for(var fragment:body.get("stage_result").get("fragments"))for(var item:fragment.get("payload").get("items"))if(decisions.putIfAbsent(WireValues.id(item.get("item_id"),false),WireValues.text(item.get("decision")))!=null)throw invalid();
        }
        var candidates=new ArrayList<Candidate>();var dependencies=new ArrayList<GenerationFlowService.Dependency>();var references=new ArrayList<String>();var missing=new ArrayList<MissingInput>();long bytes=0;
        for(var entry:original.original().closed().closure().entries()){
            if(entry.decision()==SourceDedupBarrier.Decision.MISSING)missing.add(new MissingInput("source",entry.sourceId(),original.original().day(),"source_incomplete_at_cutoff"));
            else if(entry.decision()==SourceDedupBarrier.Decision.INCLUDED&&entry.progress().state()==SourceDedupBarrier.State.PARTIAL)missing.add(new MissingInput("source",entry.sourceId(),original.original().day(),"source_partial_at_cutoff"));
        }
        for(var entity:original.entities()){
            var reservation=reservations.read(original.original().day(),entity.entityId());if(reservation!=null)bind(reservation,original,entity);
            var complete=completions.read(original.original().day(),entity.entityId(),cutoff);
            if(complete!=null){
                bind(complete.reservation(),original,entity);var validity=Objects.requireNonNull(eligibility.read(complete.version()));
                var value=CatalogAnalysisReportCandidate.create(complete,cutoff,validity.effectiveValid(),validity.blocked());candidates.add(value.candidate());dependencies.add(value.dependency());references.add(value.referenceJson());bytes+=complete.bodyJson().getBytes(StandardCharsets.UTF_8).length;
            }else{
                Boolean qualified=qualified(original,entity,contexts,decisions);if(reservation!=null&&!reservation.reservedAt().isAfter(cutoff))qualified=Boolean.TRUE;
                String reason=Boolean.FALSE.equals(qualified)?"not_new_or_reanalysis":Boolean.TRUE.equals(qualified)?"not_completed_at_cutoff":"analysis_qualification_unproven";
                var metadata=new Metadata(new ResourceRef("catalog_entity",entity.entityId(),null),null,null,null,null,null,null,original.original().day(),null,null,null);
                candidates.add(new Candidate(Group.NEW_CATALOG_ANALYSIS,metadata,null,category,false,!Boolean.FALSE.equals(qualified),null,reason));
                if(!Boolean.FALSE.equals(qualified))missing.add(new MissingInput("catalog_analysis",entity.entityId(),original.original().day(),reason));
            }
            if(bytes>CatalogDayEntityEvidenceService.MAX_BYTES)throw new GenerationFlowService.CapacityGuarded();
        }
        return new Selected(candidates,dependencies,references,missing);
    }
    /** Only actual original new decisions or permanent first version judgments establish qualification. */
    private Boolean qualified(CatalogDayEntityEvidenceService.Captured original,CatalogDayEntityEvidenceService.Entity entity,Map<String,JsonNode> contexts,Map<String,String> decisions){
        boolean unknown=false,positive=false;
        for(var member:entity.members()){
            String decision=decisions.get(member.member().itemId());if(decision==null)throw invalid();if("new".equals(decision)){positive=true;continue;}if(!"existing".equals(decision))throw invalid();
            var snapshot=contexts.get(member.member().rootId());if(snapshot==null)throw invalid();var input=FrozenApplicationContext.fromJson(snapshot.get(FrozenApplicationContext.FIELD)).input(member.member().rootId(),"report-original-qualification",member.inputHash(),0);
            if(!"2".equals(input.dedupResultVersion())){unknown=true;continue;}var entry=FrozenCatalogVersionProjections.read(snapshot.get(FrozenCatalogVersionProjections.FIELD),input).entries().get(member.member().itemId());
            if(entry==null){unknown=true;continue;}if(!entity.entityId().equals(entry.entityId()))throw invalid();var stored=judgments.read(entry.judgmentKey()).orElseThrow(CatalogDayReportComponentService::invalid);
            var value=CanonicalJson.parse(stored.json()).get("judgment_value");var projection=CanonicalJson.parse(entry.json()).get("current_proof");
            if(!category.equals(stored.category())||!entity.entityId().equals(stored.entityId())||stored.recordedAt().isAfter(original.original().cutoff())
                ||!WireValues.sha256(value.get("version_hash")).equals(WireValues.sha256(projection.get("version_hash")))||!WireValues.sha256(value.get("qualification_hash")).equals(WireValues.sha256(projection.get("qualification_hash"))))throw invalid();
            if("reanalyze".equals(stored.verdict()))positive=true;else if(!Set.of("unchanged","changed_declined").contains(stored.verdict()))throw invalid();
        }
        if(positive)return Boolean.TRUE;if(unknown)return null;return Boolean.FALSE;
    }
    private void bind(CatalogDayAnalysisReservationLedger.Stored receipt,CatalogDayEntityEvidenceService.Captured original,CatalogDayEntityEvidenceService.Entity entity){
        if(!category.equals(receipt.category())||!entity.entityId().equals(receipt.entityId())||!original.original().day().equals(receipt.day())||!original.grouping().hash().equals(receipt.groupingHash())||!original.original().closureHash().equals(receipt.closureHash())||receipt.members().size()!=entity.members().size())throw invalid();
        var members=new HashMap<String,CatalogDayEntityEvidenceService.Evidence>();for(var member:entity.members())if(members.putIfAbsent(member.member().itemId(),member)!=null)throw invalid();
        for(var saved:receipt.members()){var member=members.remove(saved.itemId());if(member==null||!saved.runId().equals(member.member().runId())||!saved.sourceId().equals(member.member().sourceId())||!saved.rootId().equals(member.member().rootId())||!saved.inputHash().equals(member.inputHash())||!saved.bodyHash().equals(member.bodyHash())||saved.revision()!=member.member().revision()||!saved.fragmentResultHash().equals(member.member().fragmentResultHash())||!saved.finalResultHash().equals(member.member().finalResultHash()))throw invalid();}
        if(!members.isEmpty())throw invalid();
    }
    private static void readonly(){if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("original catalog report component unavailable");}
}
