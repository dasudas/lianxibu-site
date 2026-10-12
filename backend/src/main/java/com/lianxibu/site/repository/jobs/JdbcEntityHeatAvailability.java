package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.*;
import java.time.*;
import java.util.*;
import java.security.MessageDigest;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Permanent first observation of an exact complete original count event. No AI task or slot is created. */
public final class JdbcEntityHeatAvailability {
    private static final String ACTOR="entity-heat-observer",REASON="Original entity count event first visible",TYPE="entity_heat_first";
    public record Observation(String eventId,String eventHash,Instant availableAt){@Override public String toString(){return "OriginalEntityHeatAvailability[redacted]";}}
    public static final class Prepared {
        private final JdbcEntityHeatEvents.Event event;private final Observation observation;private final JdbcJobRecords.Disposition receipt;
        private Prepared(JdbcEntityHeatEvents.Event event,Instant at){this.event=event;observation=new Observation(event.id(),eventHash(event),at);String target=JdbcFrozenInputs.canonical(JsonMapper.shared().createObjectNode().put("kind","entity_decision").put("id",event.id()).put("event_hash",observation.eventHash()));String body=JdbcFrozenInputs.canonical(JsonMapper.shared().createObjectNode().put("schema_version","entity-heat-first-availability-v1").put("event_id",event.id()).put("event_hash",observation.eventHash()).put("first_available_at",stamp(at)));receipt=new JdbcJobRecords.Disposition(id(event.id()),action(event.id()),TYPE,target,ACTOR,REASON,body);}
        @Override public String toString(){return "PreparedEntityHeatAvailability[redacted]";}
    }
    private final JdbcTemplate sql;private final JdbcJobRecords records;private final JdbcEntityHeatEvents events;
    public JdbcEntityHeatAvailability(DataSource source,JdbcEntityHeatEvents events){sql=new JdbcTemplate(Objects.requireNonNull(source));records=new JdbcJobRecords(source);this.events=Objects.requireNonNull(events);}
    public static Prepared prepare(JdbcEntityHeatEvents.Event event,Instant at){if(TransactionSynchronizationManager.isActualTransactionActive()||event==null||at==null||at.getNano()%1000!=0||at.isBefore(event.actedAt()))throw invalid();var body=CanonicalJson.parse(event.payloadJson());if(!"entity-decision-v2".equals(WireValues.text(body.get("schema_version")))||!body.get("entity_heat_snapshots").isArray()||body.get("entity_heat_snapshots").isEmpty())throw invalid();return new Prepared(event,at);}
    public Observation save(Prepared prepared){writer();if(prepared==null||!prepared.event.equals(events.readInWriter(prepared.event.id())))throw invalid();var previous=read(prepared.event.id());if(previous!=null){if(!previous.eventHash().equals(prepared.observation.eventHash()))throw invalid();return previous;}records.appendDispositionUtc(prepared.receipt,prepared.observation.availableAt());var saved=read(prepared.event.id());if(!prepared.observation.equals(saved))throw invalid();return saved;}
    /** Minimal original receipt lookup supports the shared writer's original-first readonly RC recovery. */
    public Observation read(String eventId){transaction();String action=action(eventId),id=id(eventId);byte[] identity=HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.IDENTITY,JsonMapper.shared().createObjectNode().put("record_kind","disposition").put("natural_key",action)));
        var rows=sql.query("SELECT id,record_kind,state,revision,call_count,max_attempts,priority_no,record_identity_hash,action_key_hash,target_type,CASE WHEN OCTET_LENGTH(target_key_json) BETWEEN 1 AND 1024 THEN target_key_json ELSE NULL END,target_key_hash,operator_id,reason,acted_at,created_at,updated_at,eligible_at,CASE WHEN OCTET_LENGTH(action_payload) BETWEEN 1 AND 4096 THEN action_payload ELSE NULL END,action_payload_hash,business_key,task_kind,target_id,root_job_id,intent_id,generation,task_type,logical_key,attempt_no,owner_job_id,purpose,stamp_id,part_no FROM jobs WHERE id=? OR action_key_hash=? OR record_identity_hash=? LIMIT 2",(r,n)->{
            String target=r.getString(11),body=r.getString(19);if(target==null||body==null)throw invalid();var targetNode=CanonicalJson.parse(target);var targetFields=Set.of("kind","id","event_hash");WireValues.object(targetNode,targetFields,targetFields);var node=CanonicalJson.parse(body);var fields=Set.of("schema_version","event_id","event_hash","first_available_at");WireValues.object(node,fields,fields);String hash=WireValues.sha256(node.get("event_hash"));Instant at=WireValues.utcMicroseconds(node.get("first_available_at"));target=JdbcFrozenInputs.canonical(targetNode);body=JdbcFrozenInputs.canonical(node);
            if(!"entity-heat-first-availability-v1".equals(WireValues.text(node.get("schema_version")))||!eventId.equals(WireValues.text(node.get("event_id")))||!"entity_decision".equals(WireValues.text(targetNode.get("kind")))||!eventId.equals(WireValues.text(targetNode.get("id")))||!hash.equals(WireValues.sha256(targetNode.get("event_hash")))||!id.equals(r.getString(1))||!"disposition".equals(r.getString(2))||!"applied".equals(r.getString(3))||number(r.getObject(4))!=0||number(r.getObject(5))!=0||r.getObject(6)!=null||number(r.getObject(7))!=0||!same(identity,r.getBytes(8))||!same(JdbcGenerationLineage.hash(action),r.getBytes(9))||!TYPE.equals(r.getString(10))||!same(JdbcGenerationLineage.hash(target),r.getBytes(12))||!ACTOR.equals(r.getString(13))||!REASON.equals(r.getString(14))||!at.equals(instant(r.getTimestamp(15,utc())))||!at.equals(instant(r.getTimestamp(16,utc())))||!at.equals(instant(r.getTimestamp(17,utc())))||!at.equals(instant(r.getTimestamp(18,utc())))||!same(JdbcGenerationLineage.hash(body),r.getBytes(20))||!("disposition:"+HexFormat.of().formatHex(identity)).equals(r.getString(21))||!"disposition".equals(r.getString(22))||!id.equals(r.getString(23)))throw invalid();for(int field=24;field<=33;field++)if(r.getObject(field)!=null)throw invalid();return new Observation(eventId,hash,at);
        },id,JdbcGenerationLineage.hash(action),identity);if(rows.isEmpty())return null;if(rows.size()!=1)throw invalid();return rows.getFirst();
    }
    public static String eventHash(JdbcEntityHeatEvents.Event event){Objects.requireNonNull(event);var n=JsonMapper.shared().createObjectNode().put("schema_version","entity-heat-event-reference-v1").put("event_id",event.id()).put("actor",event.actor()).put("reason",event.reason()).put("acted_at",stamp(event.actedAt()));n.set("target",CanonicalJson.parse(event.targetJson()));n.set("payload",CanonicalJson.parse(event.payloadJson()));return CanonicalJson.hash(CanonicalJson.Domain.BUSINESS_INPUT,n);}
    public static String action(String eventId){eventId(eventId);return "entity-heat-first:"+eventId;}
    private static String id(String eventId){eventId(eventId);return JdbcFrozenInputs.stableId("entity-heat-first",eventId);}
    private static void eventId(String value){if(value==null||!value.matches("[0-9a-f]{64}"))throw invalid();}
    private static String stamp(Instant at){return new java.time.format.DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(at);}
    private static Calendar utc(){return Calendar.getInstance(TimeZone.getTimeZone("UTC"));}
    private static Instant instant(java.sql.Timestamp value){if(value==null||value.getNanos()%1000!=0)throw invalid();return value.toInstant();}
    private static long number(Object value){if(!(value instanceof Number))throw invalid();try{return new java.math.BigInteger(value.toString()).longValueExact();}catch(RuntimeException bad){throw invalid();}}
    private static boolean same(byte[] a,byte[] b){return a!=null&&b!=null&&MessageDigest.isEqual(a,b);}
    private static void transaction(){if(!TransactionSynchronizationManager.isActualTransactionActive())throw invalid();Integer i=TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();if(TransactionSynchronizationManager.isCurrentTransactionReadOnly()?(i==null||i!=TransactionDefinition.ISOLATION_READ_COMMITTED&&i!=TransactionDefinition.ISOLATION_REPEATABLE_READ):!Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED).equals(i))throw invalid();}
    private static void writer(){transaction();if(TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw invalid();}
    private static IllegalStateException invalid(){return new IllegalStateException("original entity heat first availability unavailable");}
    @Override public String toString(){return "JdbcEntityHeatAvailability[redacted]";}
}
