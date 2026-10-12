package com.lianxibu.site.service.articles;

import com.lianxibu.site.domain.protocol.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Pure versioned retrieval policy. Historical facts must come from a trusted provider. */
public final class NewsCandidateRecall {
    public static final String FIELD="candidate_recall_v1",VERSION="entity-recall-title-trigram-v1";
    public static final int MAX_ENTITIES=10000,MAX_QUERIES=10000,MAX_PER_ITEM=20;
    private static final int MAX_BYTES=32*1024*1024;
    private NewsCandidateRecall() { }
    /** Current metadata captured under the caller's shared writer, never a historical-row reader. */
    @FunctionalInterface public interface HistorySource { History capture(); }
    public static final class CapacityGuarded extends RuntimeException {public CapacityGuarded(){super("capacity_guard");}}
    public record Entity(String id,String identityKey,String title,String summary,long revision,Instant firstRecordedAt,
                         Instant metadataAsOf,String analysisState,boolean legacy) {
        public Entity {
            WireValues.id(id,false);identityKey=identity(identityKey);title=text(title,1,512);if(summary!=null)bounded(summary,16384);
            time(firstRecordedAt);time(metadataAsOf);
            if(revision<0||revision>=CanonicalJson.MAX_SAFE_INTEGER||metadataAsOf.isBefore(firstRecordedAt)
                    ||!Set.of("pending","queued","analyzed","failed","needs_review").contains(analysisState))throw invalid();
        }
        @Override public String toString(){return "EntityCandidate[redacted]";}
    }
    public record History(Instant cutoff,List<Entity> entities) {
        public History {
            time(cutoff);if(entities==null)throw invalid();if(entities.size()>MAX_ENTITIES)throw new CapacityGuarded();
            var ordered=new TreeMap<String,Entity>(NewsCandidateRecall::utf8);var identities=new HashSet<String>();long bytes=0;
            for(var entity:entities) {
                if(entity==null||!entity.metadataAsOf().equals(cutoff)||ordered.putIfAbsent(entity.id(),entity)!=null||!identities.add(entity.identityKey()))throw invalid();
                bytes+=CanonicalJson.bytes(node(entity)).length;if(bytes>MAX_BYTES)throw new CapacityGuarded();
            }
            entities=List.copyOf(ordered.values());
        }
        @Override public String toString(){return "CandidateHistory[redacted]";}
    }
    public record Query(String itemId,String identityKey,String title) {
        public Query {WireValues.id(itemId,false);identityKey=identity(identityKey);title=text(title,1,512);}
        @Override public String toString(){return "CandidateQuery[redacted]";}
    }
    public record Selection(Instant cutoff,String historyHash,Map<String,List<Entity>> candidates,String json) {
        public Selection {var copy=new TreeMap<String,List<Entity>>(NewsCandidateRecall::utf8);candidates.forEach((id,items)->copy.put(id,List.copyOf(items)));candidates=Collections.unmodifiableMap(copy);}
        @Override public String toString(){return "CandidateRecall[redacted]";}
    }
    /** Category is part of the captured scope, not a caller-supplied model decision. */
    public record CategoryHistory(String category,History facts) {
        public CategoryHistory {
            if(!Set.of("agent","skill","workflow","model").contains(category)||facts==null)throw invalid();
            for(var entity:facts.entities())categoryIdentity(category,entity.identityKey());
        }
        @Override public String toString(){return "CategoryCandidateHistory[redacted]";}
    }
    public static Selection select(CategoryHistory history,List<Query> queries) {
        if(history==null||queries==null)throw invalid();
        for(var query:queries){if(query==null)throw invalid();categoryIdentity(history.category(),query.identityKey());}
        return select(history.category(),history.facts(),queries);
    }
    /** Original P1 profile bytes, policy and hash domain remain unchanged. */
    public static Selection select(History history,List<Query> queries) {
        return select("news_tech",history,queries);
    }
    private static Selection select(String category,History history,List<Query> queries) {
        try {
            Objects.requireNonNull(history);if(queries==null)throw invalid();if(queries.size()>MAX_QUERIES)throw new CapacityGuarded();
            var ordered=new TreeMap<String,Query>(NewsCandidateRecall::utf8);long queryBytes=0;
            for(var query:queries) {
                if(query==null||ordered.putIfAbsent(query.itemId(),query)!=null)throw invalid();
                queryBytes+=query.identityKey().getBytes(StandardCharsets.UTF_8).length+query.title().getBytes(StandardCharsets.UTF_8).length;
                if(queryBytes>MAX_BYTES)throw new CapacityGuarded();
            }
            var original=JsonMapper.shared().createObjectNode();original.put("category",category);original.put("cutoff",history.cutoff().toString());
            var all=original.putArray("entities");history.entities().forEach(entity->all.add(node(entity)));
            String hash=CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,original);
            var profile=JsonMapper.shared().createObjectNode();profile.put("schema_version",FIELD);profile.put("retrieval_version",VERSION);
            profile.put("category",category);profile.put("cutoff",history.cutoff().toString());profile.put("scope","complete_bounded_category_metadata");
            profile.put("history_hash",hash);profile.put("scanned_entities",history.entities().size());profile.put("max_per_item",MAX_PER_ITEM);
            var memberNodes=profile.putArray("members");var results=new TreeMap<String,List<Entity>>(NewsCandidateRecall::utf8);
            var tokens=new HashMap<String,Set<String>>();history.entities().forEach(e->tokens.put(e.id(),tokens(e.title())));
            for(var query:ordered.values()) {
                var queryTokens=tokens(query.title());var scores=new HashMap<String,Integer>();
                var selected=new ArrayList<Entity>();Entity exact=null;
                for(var entity:history.entities()) {
                    if(query.identityKey().equals(entity.identityKey())){exact=entity;continue;}
                    int common=0;for(String token:queryTokens)if(tokens.get(entity.id()).contains(token))common++;
                    if(common>0){selected.add(entity);scores.put(entity.id(),common);}
                }
                selected.sort(Comparator.<Entity>comparingInt(e->scores.get(e.id())).reversed()
                    .thenComparing(Entity::firstRecordedAt,Comparator.reverseOrder()).thenComparing(Entity::id,NewsCandidateRecall::utf8));
                if(exact!=null)selected.addFirst(exact);
                if(selected.size()>MAX_PER_ITEM)selected=new ArrayList<>(selected.subList(0,MAX_PER_ITEM));
                var frozen=List.copyOf(selected);results.put(query.itemId(),frozen);
                var member=memberNodes.addObject();member.put("item_id",query.itemId());
                var originalQuery=JsonMapper.shared().createObjectNode();originalQuery.put("item_id",query.itemId());originalQuery.put("identity_key",query.identityKey());originalQuery.put("title",query.title());
                member.put("query_hash",CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,originalQuery));
                var candidates=member.putArray("candidates");frozen.forEach(entity->candidates.add(node(entity)));
            }
            byte[] canonical=CanonicalJson.bytes(profile);if(canonical.length>MAX_BYTES)throw new CapacityGuarded();
            return new Selection(history.cutoff(),hash,results,new String(canonical,StandardCharsets.UTF_8));
        }catch(CapacityGuarded guarded){throw guarded;}
        catch(RuntimeException rejected){throw invalid();}
    }
    private static JsonNode node(Entity entity) {
        var node=JsonMapper.shared().createObjectNode();node.put("id",entity.id());node.put("identity_key",entity.identityKey());node.put("title",entity.title());
        if(entity.summary()==null)node.putNull("entity_summary");else node.put("entity_summary",entity.summary());
        node.put("revision",entity.revision());node.put("first_recorded_at",entity.firstRecordedAt().toString());node.put("metadata_as_of",entity.metadataAsOf().toString());node.put("analysis_state",entity.analysisState());node.put("legacy",entity.legacy());return node;
    }
    private static Set<String> tokens(String text) {
        String normalized=Normalizer.normalize(text,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).strip();int[] points=normalized.codePoints().toArray();
        if(points.length==0)return Set.of();if(points.length<=2)return Set.of(normalized);
        var result=new HashSet<String>();for(int n=0;n+2<points.length;n++)result.add(new String(points,n,3));return result;
    }
    private static void categoryIdentity(String category,String value) {
        var node=CanonicalJson.parse(value);
        String expected=switch(category){case "agent","workflow"->"github_repository";case "skill"->"github_skill";case "model"->"model_registry";default->throw invalid();};
        if(!expected.equals(WireValues.text(node.get("namespace"))))throw invalid();
    }
    private static String identity(String value) {
        if(value==null)throw invalid();bounded(value,4096);var node=CanonicalJson.parse(value);var fields=Set.of("namespace","key");WireValues.object(node,fields,fields);
        if(WireValues.text(node.get("namespace")).isBlank()||WireValues.text(node.get("key")).isBlank())throw invalid();
        return new String(CanonicalJson.bytes(node),StandardCharsets.UTF_8);
    }
    private static String text(String value,int min,int max){if(value==null||value.isBlank()||value.codePointCount(0,value.length())<min||value.codePointCount(0,value.length())>max)throw invalid();CanonicalJson.bytes(JsonMapper.shared().valueToTree(value));return value;}
    private static void bounded(String value,int bytes){if(value.getBytes(StandardCharsets.UTF_8).length>bytes)throw invalid();CanonicalJson.bytes(JsonMapper.shared().valueToTree(value));}
    private static void time(Instant value){if(value==null||value.getNano()%1000!=0)throw invalid();}
    private static int utf8(String a,String b){return Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));}
    private static IllegalStateException invalid(){return new IllegalStateException("bounded news candidate recall unavailable");}
}
