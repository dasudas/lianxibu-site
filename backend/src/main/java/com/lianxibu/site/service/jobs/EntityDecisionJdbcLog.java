package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.articles.EntityDedupService;
import com.lianxibu.site.service.articles.EntityDedupService.Decision;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Actor-bound permanent decisions, joining the member writer's transaction. */
public final class EntityDecisionJdbcLog implements EntityDedupService.DispositionLog {
    private final JdbcEntityDecisions store;
    private final String actor;
    private final Clock clock;private final JdbcEntityHeatSnapshots heat;
    /** Original v1 compatibility writer; never supplies complete historical heat facts. */
    public EntityDecisionJdbcLog(JdbcEntityDecisions store,String actor,Clock clock) {
        this.store=Objects.requireNonNull(store);this.actor=WireValues.id(actor,false);this.clock=Objects.requireNonNull(clock);this.heat=null;
    }
    /** Explicit actual native producer, including both sides of an entity split. */
    public EntityDecisionJdbcLog(JdbcEntityDecisions store,String actor,Clock clock,JdbcEntityHeatSnapshots heat){
        this.store=Objects.requireNonNull(store);this.actor=WireValues.id(actor,false);this.clock=Objects.requireNonNull(clock);this.heat=Objects.requireNonNull(heat);
    }
    private String identity(String key) {
        if(key==null||key.isBlank()||key.length()>128)throw invalid();
        var node=JsonMapper.shared().createObjectNode();node.put("scope","entity-decision-v1");node.put("actor_id",actor);node.put("action_key",key);
        return CanonicalJson.hash(CanonicalJson.Domain.ACTION,node);
    }
    @Override public Decision committed(String key,String requestHash) {
        hash(requestHash);String id=identity(key);var receipt=store.read(id,"entity_decision:"+id);if(receipt==null)return null;
        if(!actor.equals(receipt.operatorId())||!"entity_decision".equals(receipt.targetType()))throw invalid();
        var target=CanonicalJson.parse(receipt.targetKeyJson());closed(target,"kind","id");
        var decoded=decodeReceiptPayload(receipt.payloadJson(),receipt.reason());
        if(!requestHash.equals(decoded.requestHash())||!"source_run_item".equals(text(target,"kind"))||!text(target,"id").equals(decoded.itemId()))throw invalid();return decoded.result();
    }
    /** Pure shared receipt decoder; native existence and first visibility are separate proofs. */
    static record Payload(String schema,String requestHash,String operation,String itemId,String oldId,String newId,Decision result,List<EntityHeatSnapshot> snapshots){
        Payload{snapshots=List.copyOf(snapshots);}
        @Override public String toString(){return "EntityHeatReceiptPayload[redacted]";}
    }
    static Payload decodeReceiptPayload(String raw,String reason){
        var payload=CanonicalJson.parse(raw);String schema=text(payload,"schema_version");boolean v2="entity-decision-v2".equals(schema);
        if(v2)closed(payload,"schema_version","request_hash","operation","item_id","old_entity_id","new_entity_id","result","entity_heat_snapshots");else closed(payload,"schema_version","request_hash","operation","item_id","old_entity_id","new_entity_id","result");
        if(!Set.of("entity-decision-v1","entity-decision-v2").contains(schema))throw invalid();String request=text(payload,"request_hash");hash(request);String operation=text(payload,"operation"),item=text(payload,"item_id"),old=nullable(payload.get("old_entity_id")),next=nullable(payload.get("new_entity_id"));
        var result=payload.get("result");closed(result,"item_id","entity_id","state","new_contribution","occurrence_count");if(!result.get("new_contribution").isBoolean())throw invalid();
        Decision value=new Decision(text(result,"item_id"),nullable(result.get("entity_id")),text(result,"state"),result.get("new_contribution").booleanValue(),result.get("occurrence_count").isNull()?null:WireValues.nonnegativeInteger(result.get("occurrence_count")));validate(value,operation,item,old,next,reason);
        var snapshots=new ArrayList<EntityHeatSnapshot>();if(v2){var array=payload.get("entity_heat_snapshots");if(!array.isArray()||array.size()>2)throw invalid();for(var snapshot:array)snapshots.add(EntityHeatSnapshot.read(JdbcFrozenInputs.canonical(snapshot)));var complete=EntityHeatSnapshot.completeTouched(snapshots,old,next);verifyCounts(value,complete);if(!complete.equals(snapshots))throw invalid();}
        return new Payload(schema,request,operation,item,old,next,value,snapshots);
    }
    @Override public void append(String key,String requestHash,Decision result,String operation,String itemId,String oldId,String newId,String reason) {
        JdbcFrozenInputs.requireTx();hash(requestHash);validate(result,operation,itemId,oldId,newId,reason);
        String id=identity(key);var target=JsonMapper.shared().createObjectNode();target.put("kind","source_run_item");target.put("id",itemId);
        var payload=JsonMapper.shared().createObjectNode();payload.put("schema_version",heat==null?"entity-decision-v1":"entity-decision-v2");payload.put("request_hash",requestHash);payload.put("operation",operation);payload.put("item_id",itemId);payload.put("old_entity_id",oldId);payload.put("new_entity_id",newId);
        var value=payload.putObject("result");value.put("item_id",result.itemId());value.put("entity_id",result.entityId());value.put("state",result.state());value.put("new_contribution",result.newContribution());
        if(result.occurrenceCount()==null)value.putNull("occurrence_count");else value.put("occurrence_count",result.occurrenceCount());
        if(heat!=null){var snapshots=EntityHeatSnapshot.completeTouched(heat.readTouched(oldId,newId).stream().map(fact->new EntityHeatSnapshot(fact.entityId(),fact.category(),fact.revision(),fact.occurrenceCount(),fact.title(),fact.summary(),fact.firstRecordedAt(),fact.firstSeenBusinessDate(),fact.legacy())).toList(),oldId,newId);verifyCounts(result,snapshots);var array=payload.putArray("entity_heat_snapshots");snapshots.forEach(snapshot->array.add(snapshot.toJson()));}
        var receipt=new JdbcJobRecords.Disposition(id,"entity_decision:"+id,"entity_decision",JdbcFrozenInputs.canonical(target),actor,reason,JdbcFrozenInputs.canonical(payload));var at=clock.instant().truncatedTo(ChronoUnit.MICROS);
        if(heat==null)store.append(receipt,at);else store.appendUtc(receipt,at);
    }
    private static void verifyCounts(Decision result,List<EntityHeatSnapshot> snapshots){
        if(result.entityId()==null){if(!snapshots.isEmpty())throw invalid();return;}
        var own=snapshots.stream().filter(snapshot->snapshot.entityId().equals(result.entityId())).findFirst().orElseThrow(EntityDecisionJdbcLog::invalid);if(!Objects.equals(result.occurrenceCount(),own.occurrenceCount()))throw invalid();
    }
    private static void validate(Decision result,String operation,String itemId,String oldId,String newId,String reason) {
        WireValues.id(itemId,false);if(oldId!=null)WireValues.id(oldId,false);if(newId!=null)WireValues.id(newId,false);
        if(result==null||!itemId.equals(result.itemId())||!Set.of("new","existing","needs_review","associate","manual_create","exclude","split").contains(operation)
            ||reason==null||reason.isBlank()||reason.codePointCount(0,reason.length())>2048)throw invalid();
        if(result.occurrenceCount()!=null&&(result.occurrenceCount()<0||result.occurrenceCount()>CanonicalJson.MAX_SAFE_INTEGER))throw invalid();
        switch(operation) {
            case "needs_review" -> {if(oldId!=null||newId!=null||result.entityId()!=null||!"needs_review".equals(result.state())||result.newContribution()||result.occurrenceCount()!=null)throw invalid();}
            case "exclude" -> {if(oldId==null||newId!=null||!oldId.equals(result.entityId())||!"excluded".equals(result.state())||result.newContribution()||result.occurrenceCount()==null)throw invalid();}
            default -> {if(newId==null||!newId.equals(result.entityId())||!"ready".equals(result.state())||result.occurrenceCount()==null||operation.equals("split")!=(oldId!=null)||Objects.equals(oldId,newId))throw invalid();}
        }
    }
    private static void closed(JsonNode node,String... names) {var fields=Set.of(names);WireValues.object(node,fields,fields);}
    private static String text(JsonNode node,String field) {return WireValues.text(node.get(field));}
    private static String nullable(JsonNode node) {return node.isNull()?null:WireValues.id(node,false);}
    private static void hash(String value) {if(value==null||!value.matches("[0-9a-f]{64}"))throw invalid();}
    private static IllegalStateException invalid() {return new IllegalStateException("entity decision identity or proof conflict");}
}
