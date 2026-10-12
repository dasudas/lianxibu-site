package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.protocol.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Complete deterministic original-day entity membership. A pure plan grants no generation or execution permission. */
public final class CatalogDayEntityGrouping {
    public static final int MAX_BYTES=32*1024*1024;
    public record Member(String itemId,String runId,String sourceId,String rootId,String fragmentResultHash,String finalResultHash,long revision){ }
    public record Group(String entityId,String category,List<Member> members){public Group{members=List.copyOf(members);}}
    public record Excluded(String itemId,String runId,String sourceId,String rootId,String reason){ }
    public static final class Plan {
        private final String category,closureHash,json,hash,coverage;private final LocalDate day;private final Instant cutoff;
        private final List<Group> groups;private final List<Excluded> excluded;
        private Plan(String category,LocalDate day,String closureHash,Instant cutoff,List<Group> groups,List<Excluded> excluded,String coverage,String json){this.category=category;this.day=day;this.closureHash=closureHash;this.cutoff=cutoff;this.groups=List.copyOf(groups);this.excluded=List.copyOf(excluded);this.coverage=coverage;this.json=json;hash=CanonicalJson.hash(CanonicalJson.Domain.BUSINESS_INPUT,CanonicalJson.parse(json));}
        public String category(){return category;}public LocalDate day(){return day;}public String closureHash(){return closureHash;}public Instant cutoff(){return cutoff;}
        public List<Group> groups(){return groups;}public List<Excluded> excluded(){return excluded;}public String coverageState(){return coverage;}public String json(){return json;}public String hash(){return hash;}
        @Override public String toString(){return "CatalogDayEntityPlan[redacted]";}
    }
    /** Pure source values used only under the separately verified original-day provider. */
    record Source(String runId,String sourceId,String hash,String json){ }
    private CatalogDayEntityGrouping(){ }
    public static Plan group(CatalogDayOriginalFinalsService.Captured original){
        Objects.requireNonNull(original);var sources=original.sources().stream().map(s->new Source(s.receipt().runId(),s.receipt().sourceId(),s.result().hash(),s.result().json())).toList();
        return values(original.category(),original.day(),original.closureHash(),original.cutoff(),original.closed().closure().entries(),sources);
    }
    static Plan values(String category,LocalDate day,String closureHash,Instant cutoff,List<SourceDedupBarrier.Entry> entries,List<Source> sources){
        try{
            if(!Set.of("agent","skill","workflow","model").contains(category)||day==null||closureHash==null||!closureHash.matches("[0-9a-f]{64}")||cutoff==null||cutoff.getNano()%1000!=0||entries==null||sources==null)throw invalid();
            var expected=new TreeMap<String,SourceDedupBarrier.Progress>();var directoryIds=new HashSet<String>();boolean partial=false;
            for(var entry:entries){if(entry==null||!directoryIds.add(entry.sourceId()))throw invalid();if(entry.decision()==SourceDedupBarrier.Decision.MISSING)partial=true;if(entry.decision()==SourceDedupBarrier.Decision.INCLUDED){var progress=entry.progress();if(progress==null||!category.equals(progress.category())||!day.equals(progress.day())||!entry.sourceId().equals(progress.sourceId())||progress.availableAt().isAfter(cutoff)||progress.runGeneration()!=1||expected.putIfAbsent(entry.sourceId(),progress)!=null)throw invalid();}}
            var grouped=new TreeMap<String,List<Member>>();var exclusions=new ArrayList<Excluded>();var seenItems=new HashSet<String>();var seenRuns=new HashSet<String>();long bytes=0;
            for(var source:sources.stream().sorted(Comparator.comparing(Source::sourceId)).toList()){
                if(source==null||source.json()==null||source.json().getBytes(StandardCharsets.UTF_8).length>CatalogSourceFinalResult.MAX_BYTES)throw invalid();bytes+=source.json().getBytes(StandardCharsets.UTF_8).length;if(bytes>MAX_BYTES)throw new GenerationFlowService.CapacityGuarded();
                var progress=expected.remove(source.sourceId());if(progress==null||!source.runId().equals(progress.runId())||!source.hash().equals(progress.proofHash())||!seenRuns.add(source.runId()))throw invalid();var body=CanonicalJson.parse(source.json());
                if(!source.hash().equals(CanonicalJson.hash(CanonicalJson.Domain.CONTENT,body))||!category.equals(WireValues.text(body.get("category")))||!day.equals(WireValues.businessDate(body.get("business_date")))||!source.runId().equals(WireValues.id(body.get("run_id"),false))||!source.sourceId().equals(WireValues.id(body.get("source_id"),true))||WireValues.nonnegativeInteger(body.get("generation"))!=1)throw invalid();
                String coverage=WireValues.text(body.get("coverage_state"));if(!Set.of("complete","partial").contains(coverage))throw invalid();partial|="partial".equals(coverage);
                var stage=body.get("stage_result");if(stage==null||!stage.get("fragments").isArray())throw invalid();long applied=0;var roots=new HashSet<String>();
                for(var fragment:stage.get("fragments")){
                    String root=WireValues.id(fragment.get("root_id"),false),result=WireValues.sha256(fragment.get("result_hash"));if(!roots.add(root)||!fragment.get("members").isArray())throw invalid();var itemIds=new HashSet<String>();for(var item:fragment.get("payload").get("items"))if(!itemIds.add(WireValues.id(item.get("item_id"),false)))throw invalid();
                    for(var member:fragment.get("members")){
                        String id=WireValues.id(member.get("itemId"),false),state=WireValues.text(member.get("state"));long revision=WireValues.nonnegativeInteger(member.get("revision"));
                        if(!seenItems.add(id)||!itemIds.remove(id)||!category.equals(WireValues.text(member.get("category")))||!source.runId().equals(WireValues.id(member.get("runId"),false))||!source.sourceId().equals(WireValues.id(member.get("sourceId"),true))||!day.equals(WireValues.businessDate(member.get("businessDate")))||!day.equals(WireValues.businessDate(member.get("targetBusinessDate")))||WireValues.nonnegativeInteger(member.get("generation"))!=1||!"production".equals(WireValues.text(member.get("activationStage"))))throw invalid();
                        if("ready".equals(state)){String entity=WireValues.id(member.get("entityId"),false);grouped.computeIfAbsent(entity,k->new ArrayList<>()).add(new Member(id,source.runId(),source.sourceId(),root,result,source.hash(),revision));}
                        else if("needs_review".equals(state)&&member.get("entityId").isNull())exclusions.add(new Excluded(id,source.runId(),source.sourceId(),root,"dedup_needs_review"));else throw invalid();applied++;
                    }
                    if(!itemIds.isEmpty())throw invalid();
                }
                if(applied!=WireValues.nonnegativeInteger(body.get("applied_member_count")))throw invalid();
            }
            if(!expected.isEmpty())throw invalid();var groups=new ArrayList<Group>();grouped.forEach((id,members)->groups.add(new Group(id,category,members.stream().sorted(Comparator.comparing(Member::itemId)).toList())));exclusions.sort(Comparator.comparing(Excluded::itemId));
            var output=JsonMapper.shared().createObjectNode().put("schema_version","catalog-day-entity-plan-v1").put("category",category).put("business_date",day.toString()).put("closure_hash",closureHash).put("cutoff",new DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(cutoff)).put("coverage_state",partial?"partial":"complete");
            output.set("groups",JsonMapper.shared().valueToTree(groups));output.set("excluded_members",JsonMapper.shared().valueToTree(exclusions));var omissions=output.putArray("source_decisions");for(var entry:entries){var n=omissions.addObject().put("source_id",entry.sourceId()).put("decision",entry.decision().name().toLowerCase(Locale.ROOT)).put("reason",entry.reason());if(entry.progress()==null)n.putNull("proof_hash");else n.put("proof_hash",entry.progress().proofHash());}
            byte[] raw=CanonicalJson.bytes(output);if(raw.length>MAX_BYTES)throw new GenerationFlowService.CapacityGuarded();return new Plan(category,day,closureHash,cutoff,groups,exclusions,partial?"partial":"complete",new String(raw,StandardCharsets.UTF_8));
        }catch(GenerationFlowService.CapacityGuarded guarded){throw guarded;}catch(RuntimeException rejected){throw invalid();}
    }
    private static IllegalStateException invalid(){return new IllegalStateException("complete original catalog entity membership unavailable");}
}
