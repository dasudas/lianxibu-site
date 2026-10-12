package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.articles.*;
import java.time.*;
import java.util.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import tools.jackson.databind.JsonNode;

/** Actual original-day triggers. A returned selection can only be used after this issuer's writer verification. */
public final class CatalogDayAnalysisEligibilityService {
    public enum Trigger { NEW_ENTITY, VERSION_RECOMMENDED }
    public record Selection(CatalogDayEntityEvidenceService.Entity entity,CatalogEntityAnalysisHeaderService.Header header,
                            Trigger trigger,String logicalKey,long generation,String intentId,List<String> judgments){
        public Selection{judgments=List.copyOf(judgments);}
        @Override public String toString(){return "CatalogDayAnalysisSelection[redacted]";}
    }
    public record Excluded(String entityId,String reason){ }
    private record State(List<Selection> selected,List<Excluded> excluded,Map<String,CatalogEntityAnalysisHeaderService.Header> headers,List<String> judgments){
        private State{selected=List.copyOf(selected);excluded=List.copyOf(excluded);headers=Map.copyOf(headers);judgments=List.copyOf(judgments);}
    }
    private record Origin(String root,long generation){ }
    public static final class Captured {
        private final Object issuer;private final CatalogDayAnalysisCommitPermitService.Captured original;private final State state;
        private Captured(Object issuer,CatalogDayAnalysisCommitPermitService.Captured original,State state){this.issuer=issuer;this.original=original;this.state=state;}
        public CatalogDayEntityEvidenceService.Captured evidence(){return original.evidence();}
        public List<Selection> selected(){return state.selected();}public List<Excluded> excluded(){return state.excluded();}
        @Override public String toString(){return "CatalogDayAnalysisEligibility[redacted]";}
    }
    private final Object issuer=new Object();private final String category;private final CatalogDayAnalysisCommitPermitService original;
    private final CatalogEntityAnalysisHeaderService entities;private final EntityInputEvidenceService currentMembers;
    private final FrozenSourceStagePlanService plans;private final CatalogVersionJudgmentLedger ledger;private final JdbcFrozenSourcePlans owners;private final JdbcJobRecords records;private final TransactionTemplate reads;
    public CatalogDayAnalysisEligibilityService(CatalogDayAnalysisCommitPermitService original,CatalogEntityAnalysisHeaderService entities,
            EntityInputEvidenceService currentMembers,FrozenSourceStagePlanService plans,CatalogVersionJudgmentLedger ledger,JdbcFrozenSourcePlans owners,JdbcJobRecords records,
            PlatformTransactionManager manager,String category){
        this.category=switch(Objects.requireNonNull(category)){case "agent","skill","workflow","model"->category;default->throw invalid();};this.original=Objects.requireNonNull(original);this.entities=Objects.requireNonNull(entities);this.currentMembers=Objects.requireNonNull(currentMembers);this.plans=Objects.requireNonNull(plans);this.ledger=Objects.requireNonNull(ledger);this.owners=Objects.requireNonNull(owners);this.records=Objects.requireNonNull(records);reads=new TransactionTemplate(Objects.requireNonNull(manager));reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public Captured capture(LocalDate day){outside();return capture(original.capture(day));}
    public Captured captureOriginal(LocalDate day){outside();return capture(original.captureOriginal(day));}
    private Captured capture(CatalogDayAnalysisCommitPermitService.Captured day){return day==null?null:new Captured(issuer,day,Objects.requireNonNull(reads.execute(s->state(day.evidence(),false))));}
    /** Holds original source/day, current entity/member and permanent first-judgment locks in the caller's RC writer. */
    public List<Selection> verify(Captured captured){
        JdbcFrozenInputs.requireTx();if(captured==null||captured.issuer!=issuer)throw invalid();var day=original.verify(captured.original);var fresh=state(day,true);if(!fresh.equals(captured.state))throw invalid();
        for(var selected:fresh.selected()){
            // The owner is already present and locked. ensureIntent verifies the native natural identity;
            // a damaged identity cannot create a replacement because the original ID already exists.
            if(selected.trigger()==Trigger.VERSION_RECOMMENDED&&!selected.intentId().equals(records.ensureIntent(new JdbcJobRecords.Intent(selected.intentId(),selected.logicalKey(),selected.generation(),category+"_analysis",day.original().day()),day.original().cutoff())))throw invalid();
            var expected=new TreeMap<String,CatalogDayEntityEvidenceService.Evidence>();for(var member:selected.entity().members())if(expected.putIfAbsent(member.member().itemId(),member)!=null)throw invalid();
            var current=currentMembers.read(selected.entity().entityId(),category,expected.keySet(),day.original().cutoff());
            if(current.entityRevision()!=selected.header().revision()||current.members().size()!=expected.size())throw invalid();
            for(var member:current.members()){
                var old=expected.remove(member.id());if(old==null||member.revision()!=old.member().revision()||!category.equals(member.category())||!member.identityKey().equals(old.identityKey())||!member.title().equals(old.title())||!Objects.equals(member.summary(),old.summary())||!member.payloadJson().equals(old.bodyJson()))throw invalid();
            }
            if(!expected.isEmpty())throw invalid();
        }
        return captured.state.selected();
    }
    private State state(CatalogDayEntityEvidenceService.Captured day,boolean writer){
        if(!category.equals(day.original().category()))throw invalid();var all=day.entities();var headers=new TreeMap<String,CatalogEntityAnalysisHeaderService.Header>();
        for(int first=0;first<all.size();first+=100){var ids=new TreeSet<String>();all.subList(first,Math.min(first+100,all.size())).forEach(e->ids.add(e.entityId()));headers.putAll(writer?entities.lockInWriter(ids):entities.findInSnapshot(ids));}
        var contexts=new HashMap<String,JsonNode>();var decisions=new HashMap<String,String>();
        for(var source:day.original().sources()){
            var body=CanonicalJson.parse(source.result().json());var reference=FrozenSourceStagePlan.Reference.fromJson(JdbcFrozenInputs.canonical(body.get("plan_ref")));var plan=writer?plans.read(reference):plans.readImmutable(reference);
            for(var chunk:plan.chunks())if(contexts.putIfAbsent(chunk.rootId(),CanonicalJson.parse(chunk.snapshotJson()))!=null)throw invalid();
            for(var fragment:body.get("stage_result").get("fragments"))for(var item:fragment.get("payload").get("items"))if(decisions.putIfAbsent(WireValues.id(item.get("item_id"),false),WireValues.text(item.get("decision")))!=null)throw invalid();
        }
        var selected=new ArrayList<Selection>();var excluded=new ArrayList<Excluded>();var allJudgments=new TreeMap<String,String>();
        for(var entity:all){
            var header=headers.get(entity.entityId());if(header==null||!category.equals(header.category()))throw invalid();
            var positives=new ArrayList<CatalogVersionJudgmentLedger.Stored>();var roots=new HashMap<String,Origin>();var judgments=new TreeMap<String,String>();
            for(var member:entity.members()){
                var snapshot=contexts.get(member.member().rootId());if(snapshot==null)throw invalid();var input=FrozenApplicationContext.fromJson(snapshot.get(FrozenApplicationContext.FIELD)).input(member.member().rootId(),"original-day-eligibility",member.inputHash(),0);
                if(!"2".equals(input.dedupResultVersion())||!"existing".equals(decisions.get(member.member().itemId())))continue;
                var entry=FrozenCatalogVersionProjections.read(snapshot.get(FrozenCatalogVersionProjections.FIELD),input).entries().get(member.member().itemId());if(entry==null)continue;
                if(!entry.entityId().equals(entity.entityId()))throw invalid();var stored=(writer?ledger.lockAndRead(entry.judgmentKey()):ledger.read(entry.judgmentKey())).orElseThrow(CatalogDayAnalysisEligibilityService::invalid);
                var projection=CanonicalJson.parse(entry.json());var value=CanonicalJson.parse(stored.json()).get("judgment_value");var current=projection.get("current_proof");
                if(stored.recordedAt().isAfter(day.original().cutoff())||!entity.entityId().equals(stored.entityId())||!category.equals(stored.category())||!WireValues.sha256(current.get("version_hash")).equals(WireValues.sha256(value.get("version_hash")))||!WireValues.sha256(current.get("qualification_hash")).equals(WireValues.sha256(value.get("qualification_hash"))))throw invalid();
                if(judgments.putIfAbsent(stored.judgmentKey(),stored.json())!=null&&!judgments.get(stored.judgmentKey()).equals(stored.json()))throw invalid();allJudgments.put(stored.judgmentKey(),stored.json());
                if("reanalyze".equals(stored.verdict())){
                    if(stored.generation()>header.generation())throw invalid();positives.add(stored);var root=new Origin(WireValues.id(projection.get("root_id"),false),WireValues.nonnegativeInteger(projection.get("generation")));var previous=roots.putIfAbsent(stored.intentId(),root);if(previous!=null&&!previous.equals(root))throw invalid();
                }
            }
            if(entity.members().size()>100){excluded.add(new Excluded(entity.entityId(),"member_capacity"));continue;}
            if("pending".equals(header.state())&&header.generation()==0){selected.add(new Selection(entity,header,Trigger.NEW_ENTITY,logicalKey(category,entity.entityId()),1,null,List.copyOf(judgments.values())));continue;}
            if(!"queued".equals(header.state())){excluded.add(new Excluded(entity.entityId(),"needs_review".equals(header.state())?"entity_needs_review":"generation_settled"));continue;}
            var matching=positives.stream().filter(p->p.generation()==header.generation()).toList();if(matching.isEmpty()){excluded.add(new Excluded(entity.entityId(),"not_triggered_by_original_day"));continue;}
            String intent=matching.getFirst().intentId();if(matching.stream().anyMatch(p->!intent.equals(p.intentId())))throw invalid();var origin=roots.get(intent);var root=writer?owners.lockOwner(origin.root()):owners.immutableOwner(origin.root());
            if(root==null||!"logical_task".equals(root.kind())||!(category+"_analysis").equals(root.type())||root.key()==null||root.key().isBlank()||!Arrays.equals(root.keyHash(),JdbcGenerationLineage.hash(root.key()))||root.generation()!=origin.generation()||root.generation()<1||root.generation()>=header.generation())throw invalid();
            var pending=writer?owners.lockOwner(intent):owners.immutableOwner(intent);if(pending==null||!"generation_intent".equals(pending.kind())||!root.key().equals(pending.key())||!Arrays.equals(root.keyHash(),pending.keyHash())||pending.generation()!=header.generation()||!(category+"_analysis").equals(pending.type())||!day.original().day().equals(pending.businessDate())||!intent.equals(JdbcFrozenInputs.stableId("intent",root.key()+":"+header.generation())))throw invalid();
            boolean frozen=writer?owners.hasRoots(intent):owners.immutableHasRoots(intent);if(!"waiting_input".equals(pending.state())||frozen){excluded.add(new Excluded(entity.entityId(),"original_intent_started"));continue;}
            selected.add(new Selection(entity,header,Trigger.VERSION_RECOMMENDED,root.key(),header.generation(),intent,List.copyOf(judgments.values())));
        }
        return new State(selected,excluded,headers,List.copyOf(allJudgments.values()));
    }
    public static String logicalKey(String category,String entity){if(!Set.of("agent","skill","workflow","model").contains(category))throw invalid();WireValues.id(entity,false);return "catalog-analysis:"+category+":"+entity;}
    private static void outside(){if(TransactionSynchronizationManager.isActualTransactionActive())throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("original catalog day analysis eligibility unavailable");}
}
