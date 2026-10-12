package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.domain.sources.SourceDayRoster;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Immutable full day directory in existing typed jobs records. No model tasks are created. */
public final class JdbcSourceDayRosters {
    private static final int CHUNK_BYTES=96*1024,MAX_PART_BYTES=140000,MAX_PARTS=12;
    private static final String TYPE="source_day_roster",STAMP="source-day-roster-v1";
    private static final ZoneId SHANGHAI=ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate sql;private final JdbcJobRecords records;
    public record Frozen(LocalDate day,String ownerId,Instant registeredAt,SourceDayRoster.Evidence evidence){
        @Override public String toString(){return "FrozenSourceDayRoster[redacted]";}
    }
    private record Part(String id,String owner,String purpose,String stamp,long number,String payload,byte[] hash){ }
    public JdbcSourceDayRosters(DataSource source){Objects.requireNonNull(source);sql=new JdbcTemplate(source);records=new JdbcJobRecords(source);}
    public Frozen save(LocalDate day,SourceDayRoster.Evidence evidence,Instant registeredAt){
        requireTransaction(true);Objects.requireNonNull(evidence);window(day,evidence.capturedAt());window(day,registeredAt);
        if(registeredAt.isBefore(evidence.capturedAt()))throw invalid();
        Frozen previous=read(day);
        if(previous!=null){same(previous,evidence,registeredAt);return previous;}
        String owner=owner(day),logical=logical(day);byte[] bytes=evidence.metadataJson().getBytes(StandardCharsets.UTF_8);
        var manifest=JsonMapper.shared().createObjectNode().put("schema_version",STAMP).put("business_date",day.toString())
                .put("owner_job_id",owner).put("registered_at",stamp(registeredAt)).put("metadata_hash",evidence.hash()).put("metadata_bytes",bytes.length);
        var descriptions=manifest.putArray("parts");var payloads=new ArrayList<String>();
        for(int offset=0;offset<bytes.length;offset+=CHUNK_BYTES){
            int number=payloads.size()+1;
            var chunk=JsonMapper.shared().createObjectNode().put("schema_version","source-day-roster-chunk-v1")
                    .put("bytes_base64",Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes,offset,Math.min(bytes.length,offset+CHUNK_BYTES))));
            String body=canonical(chunk);payloads.add(body);
            descriptions.addObject().put("part_no",number).put("payload_hash",payloadHash(chunk));
        }
        if(payloads.size()+1>MAX_PARTS)throw new SourceDayRoster.CapacityExceeded();
        if(!owner.equals(records.ensureIntent(new JdbcJobRecords.Intent(owner,logical,1,TYPE,day),registeredAt)))throw invalid();
        put(owner,0,canonical(manifest),registeredAt);
        for(int index=0;index<payloads.size();index++)put(owner,index+1,payloads.get(index),registeredAt);
        var result=read(day);if(result==null)throw invalid();same(result,evidence,registeredAt);return result;
    }
    /** Null is a truly absent day. An existing incomplete or damaged archive is an error. */
    public Frozen read(LocalDate day){
        requireTransaction(false);String owner=owner(day);var parent=sql.query("SELECT id,record_kind,task_type,logical_key,logical_key_hash,generation,business_date,state,revision,call_count,max_attempts,priority_no,input_state,record_identity_hash FROM jobs WHERE id=?",
                (rs,index)->new Object[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getBytes(5),rs.getObject(6),rs.getString(7),rs.getString(8),rs.getObject(9),rs.getObject(10),rs.getObject(11),rs.getObject(12),rs.getString(13),rs.getBytes(14)},owner);
        if(parent.isEmpty()){
            Long orphan=sql.queryForObject("SELECT COUNT(*) FROM jobs WHERE owner_job_id=?",Long.class,owner);
            if(orphan==null||orphan!=0)throw invalid();return null;
        }
        if(parent.size()!=1)throw invalid();Object[] p=parent.getFirst();String logical=logical(day);
        if(!owner.equals(p[0])||!"generation_intent".equals(p[1])||!TYPE.equals(p[2])||!logical.equals(p[3])||!equalHash(p[4],rawHash(logical))
                ||number(p[5])!=1||!day.toString().equals(p[6])||!"waiting_input".equals(p[7])||number(p[8])!=0||number(p[9])!=0
                ||p[10]!=null||number(p[11])!=0||!"waiting_input".equals(p[12])||!equalHash(p[13],identity("generation_intent",logical+"\u0000"+1)))throw invalid();
        Long count=sql.queryForObject("SELECT COUNT(*) FROM jobs WHERE record_kind='snapshot_part' AND owner_job_id=? AND purpose='input' AND stamp_id=?",Long.class,owner,STAMP);
        if(count==null||count<2||count>MAX_PARTS)throw invalid();
        var parts=sql.query("SELECT id,owner_job_id,purpose,stamp_id,part_no,CASE WHEN OCTET_LENGTH(payload_json)<=140000 THEN payload_json ELSE NULL END,payload_hash,record_identity_hash,state,revision,call_count,max_attempts,priority_no FROM jobs WHERE record_kind='snapshot_part' AND owner_job_id=? AND purpose='input' AND stamp_id=? ORDER BY part_no,id LIMIT 13",
                (rs,index)->{
                    long n=number(rs.getObject(5));String id=partId(owner,n),body=rs.getString(6);
                    if(!id.equals(rs.getString(1))||!owner.equals(rs.getString(2))||!"input".equals(rs.getString(3))||!STAMP.equals(rs.getString(4))||body==null||body.getBytes(StandardCharsets.UTF_8).length>MAX_PART_BYTES
                            ||!equalHash(rs.getBytes(8),identity("snapshot_part",owner+"\u0000input\u0000"+STAMP+"\u0000"+n))||!"frozen".equals(rs.getString(9))||number(rs.getObject(10))!=0||number(rs.getObject(11))!=0||rs.getObject(12)!=null||number(rs.getObject(13))!=0)throw invalid();
                    return new Part(id,owner,"input",STAMP,n,body,rs.getBytes(7));
                },owner,STAMP);
        if(parts.size()!=count)throw invalid();
        for(int index=0;index<parts.size();index++)if(parts.get(index).number()!=index||!equalHash(parts.get(index).hash(),HexFormat.of().parseHex(payloadHash(CanonicalJson.parse(parts.get(index).payload())))))throw invalid();
        JsonNode manifest=CanonicalJson.parse(parts.getFirst().payload());closed(manifest,"schema_version","business_date","owner_job_id","registered_at","metadata_hash","metadata_bytes","parts");
        if(!STAMP.equals(text(manifest,"schema_version"))||!day.equals(WireValues.businessDate(manifest.get("business_date")))||!owner.equals(text(manifest,"owner_job_id")))throw invalid();
        Instant registeredAt=WireValues.utcMicroseconds(manifest.get("registered_at"));window(day,registeredAt);
        long size=WireValues.nonnegativeInteger(manifest.get("metadata_bytes"));if(size<1||size>SourceDayRoster.MAX_METADATA_BYTES)throw invalid();
        JsonNode descriptions=manifest.get("parts");if(!descriptions.isArray()||descriptions.size()!=parts.size()-1)throw invalid();
        var bytes=new ByteArrayOutputStream();
        for(int index=1;index<parts.size();index++){
            JsonNode description=descriptions.get(index-1);closed(description,"part_no","payload_hash");JsonNode chunk=CanonicalJson.parse(parts.get(index).payload());closed(chunk,"schema_version","bytes_base64");
            if(WireValues.nonnegativeInteger(description.get("part_no"))!=index||!WireValues.sha256(description.get("payload_hash")).equals(payloadHash(chunk))||!"source-day-roster-chunk-v1".equals(text(chunk,"schema_version")))throw invalid();
            String encoded=text(chunk,"bytes_base64");byte[] decoded;
            try{decoded=Base64.getDecoder().decode(encoded);}catch(IllegalArgumentException bad){throw invalid();}
            if(decoded.length<1||decoded.length>CHUNK_BYTES||index<parts.size()-1&&decoded.length!=CHUNK_BYTES||!encoded.equals(Base64.getEncoder().encodeToString(decoded))||bytes.size()+decoded.length>size)throw invalid();bytes.writeBytes(decoded);
        }
        if(bytes.size()!=size)throw invalid();byte[] original=bytes.toByteArray();SourceDayRoster.Evidence evidence=decode(original);
        window(day,evidence.capturedAt());if(registeredAt.isBefore(evidence.capturedAt())||!evidence.hash().equals(WireValues.sha256(manifest.get("metadata_hash")))||!Arrays.equals(original,evidence.metadataJson().getBytes(StandardCharsets.UTF_8)))throw invalid();
        return new Frozen(day,owner,registeredAt,evidence);
    }
    private static SourceDayRoster.Evidence decode(byte[] bytes){
        JsonNode root=CanonicalJson.parse(bytes);closed(root,"schema_version","captured_at","source_count","sources");
        if(!"source-day-roster-raw-v1".equals(text(root,"schema_version"))||!root.get("sources").isArray())throw invalid();var sources=new ArrayList<SourceDayRoster.RawSource>();
        for(var row:root.get("sources")){
            closed(row,"activation_stage","category","config_revision","config_valid_until","draft_present","effective_config_hash","effective_present","id","legacy_state","source_revision");
            if(!row.get("draft_present").isBoolean()||!row.get("effective_present").isBoolean())throw invalid();
            sources.add(new SourceDayRoster.RawSource(text(row,"id"),nullableText(row,"category"),WireValues.nonnegativeInteger(row.get("source_revision")),WireValues.nonnegativeInteger(row.get("config_revision")),text(row,"activation_stage"),text(row,"legacy_state"),row.get("config_valid_until").isNull()?null:WireValues.utcMicroseconds(row.get("config_valid_until")),row.get("draft_present").booleanValue(),row.get("effective_present").booleanValue(),nullableText(row,"effective_config_hash")));
        }
        return new SourceDayRoster.Evidence(WireValues.utcMicroseconds(root.get("captured_at")),WireValues.nonnegativeInteger(root.get("source_count")),sources);
    }
    private void put(String owner,int n,String payload,Instant now){records.putPart(new JdbcJobRecords.Part(partId(owner,n),owner,"input",STAMP,n,payload,payloadHash(CanonicalJson.parse(payload))),now);}
    public static String owner(LocalDate day){logical(day);return JdbcFrozenInputs.stableId("source-day-roster",day.toString());}
    private static String logical(LocalDate day){if(day==null||day.getYear()<1970||day.getYear()>9999)throw invalid();return "source-day-roster:"+day;}
    private static String partId(String owner,long n){return JdbcFrozenInputs.stableId("source-day-roster-part",owner+":"+n);}
    private static void same(Frozen original,SourceDayRoster.Evidence proposed,Instant registeredAt){if(!original.registeredAt().equals(registeredAt)||!original.evidence().hash().equals(proposed.hash())||!original.evidence().metadataJson().equals(proposed.metadataJson()))throw invalid();}
    private static void window(LocalDate day,Instant value){logical(day);Objects.requireNonNull(value);if(value.getNano()%1000!=0||value.isBefore(day.plusDays(1).atTime(0,10).atZone(SHANGHAI).toInstant())||!value.isBefore(day.plusDays(1).atTime(4,30).atZone(SHANGHAI).toInstant()))throw invalid();}
    private static void requireTransaction(boolean writer){if(!TransactionSynchronizationManager.isActualTransactionActive()||writer&&TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new IllegalStateException("source day roster transaction required");}
    private static long number(Object value){if(!(value instanceof Number))throw invalid();try{var number=new java.math.BigInteger(value.toString());if(number.signum()<0)return -1;return number.longValueExact();}catch(RuntimeException bad){throw invalid();}}
    private static byte[] identity(String kind,String natural){var node=JsonMapper.shared().createObjectNode().put("record_kind",kind).put("natural_key",natural);return HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.IDENTITY,node));}
    private static byte[] rawHash(String text){try{return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static boolean equalHash(Object left,byte[] right){return left instanceof byte[] bytes&&MessageDigest.isEqual(bytes,right);}
    private static String payloadHash(JsonNode body){return CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,body);}
    private static String canonical(JsonNode body){return new String(CanonicalJson.bytes(body),StandardCharsets.UTF_8);}
    private static String text(JsonNode n,String field){return WireValues.text(n.get(field));}
    private static String nullableText(JsonNode n,String field){return n.get(field).isNull()?null:text(n,field);}
    private static void closed(JsonNode body,String...fields){var set=Set.of(fields);WireValues.object(body,set,set);}
    private static String stamp(Instant value){return new java.time.format.DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(value);}
    private static IllegalStateException invalid(){return new IllegalStateException("immutable source day roster unavailable");}
    @Override public String toString(){return "JdbcSourceDayRosters[redacted]";}
}
