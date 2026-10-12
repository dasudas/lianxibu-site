package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.protocol.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Original external evidence ledger. Content, slot, request, input, and budgets are unchanged. */
public final class JdbcOwnerExternalResolution {
    public record Command(String canonical,String inputHash){
        public Command {WireValues.sha256(JsonMapper.shared().getNodeFactory().stringNode(inputHash));var value=body(canonical);canonical=JdbcFrozenInputs.canonical(value);}
        String hash(){return CanonicalJson.hash(CanonicalJson.Domain.ACTION,CanonicalJson.parse(canonical));}
        @Override public String toString(){return "OwnerExternalResolutionCommand[redacted]";}
    }
    public record Resolved(String operationRef,String rootId,String attemptId,long revision,String externalState,boolean rootResultRetained){ }
    /** The actual consumer must verify every typed result against this original native INPUT. */
    @FunctionalInterface public interface Verifier {void verify(JdbcOwnerExternalAttemptReads.Frame frame,String canonicalEvidence);}
    public static final class Rejected extends RuntimeException {public Rejected(String code){super(code);}}
    private final JdbcTemplate sql;private final JdbcJobRecords records;private final JdbcOwnerExternalAttemptReads originals;
    public JdbcOwnerExternalResolution(DataSource source){sql=new JdbcTemplate(Objects.requireNonNull(source));records=new JdbcJobRecords(source);originals=new JdbcOwnerExternalAttemptReads(source);}

    /** Original receipt is validated before comparing a later command or inspecting current eligibility. */
    public Resolved committed(String key,Command command,JdbcOwnerExternalAttemptReads.Frame frame,Verifier verifier){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw unavailable();Objects.requireNonNull(command);Objects.requireNonNull(verifier);
        var rows=sql.query("SELECT id,record_kind,operator_id,reason,target_type,CASE WHEN OCTET_LENGTH(target_key_json) BETWEEN 1 AND 16384 THEN target_key_json ELSE NULL END,target_key_hash,CASE WHEN OCTET_LENGTH(action_payload) BETWEEN 1 AND 16384 THEN action_payload ELSE NULL END,action_payload_hash,call_count,max_attempts,priority_no,state,revision,record_identity_hash FROM jobs WHERE action_key_hash=UNHEX(?)",(r,n)->new Object[]{r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getBytes(7),r.getString(8),r.getBytes(9),r.getObject(10,Integer.class),r.getObject(11,Integer.class),r.getObject(12,Integer.class),r.getString(13),r.getObject(14,Long.class),r.getBytes(15)},JdbcResultState.sha(key));
        if(rows.isEmpty())return null;if(rows.size()!=1)throw unavailable();var row=rows.getFirst();
        try{
            if(!operation(key).equals(row[0])||!"disposition".equals(row[1])||!"job_external_resolution".equals(row[4])||row[5]==null||row[7]==null||!Integer.valueOf(0).equals(row[9])||row[10]!=null||!Integer.valueOf(0).equals(row[11])||!"applied".equals(row[12])||!Long.valueOf(0).equals(row[13])||!Arrays.equals((byte[])row[14],identity("disposition",key)))throw unavailable();
            var target=CanonicalJson.parse((String)row[5]);closed(target,"root_id","attempt_id","expected_revision");var receipt=CanonicalJson.parse((String)row[7]);
            closed(receipt,"schema_version","root_id","attempt_id","input_hash","action_hash","expected_revision","revision","external_state","root_result_retained","review_manifest","result_manifest");
            if(!Arrays.equals((byte[])row[6],digest(JdbcFrozenInputs.canonical(target)))||!Arrays.equals((byte[])row[8],digest(JdbcFrozenInputs.canonical(receipt)))||!"owner-external-resolution-receipt-v1".equals(text(receipt,"schema_version")))throw unavailable();
            String root=WireValues.id(receipt.get("root_id"),false),attempt=WireValues.id(receipt.get("attempt_id"),false),hash=WireValues.sha256(receipt.get("action_hash"));long expected=WireValues.nonnegativeInteger(receipt.get("expected_revision"));
            if(expected>=CanonicalJson.MAX_SAFE_INTEGER||WireValues.nonnegativeInteger(receipt.get("revision"))!=expected+1||!root.equals(text(target,"root_id"))||!attempt.equals(text(target,"attempt_id"))||expected!=WireValues.nonnegativeInteger(target.get("expected_revision"))||!receipt.get("root_result_retained").isBoolean())throw unavailable();
            binds(frame,root,attempt,WireValues.sha256(receipt.get("input_hash")));
            String evidence=readReview(root,attempt,frame.identity().inputHash(),hash,receipt.get("review_manifest"));var old=body(evidence);
            if(!row[2].equals(text(old,"operator_id"))||!row[3].equals(text(old,"reason"))||!root.equals(text(old,"root_id"))||!attempt.equals(text(old,"attempt_id"))||expected!=WireValues.nonnegativeInteger(old.get("expected_revision"))||!text(receipt,"external_state").equals(text(old,"external_state")))throw unavailable();
            if(receipt.get("root_result_retained").booleanValue()&&!"succeeded".equals(text(old,"external_state")))throw unavailable();
            verifier.verify(frame,evidence);verifyResult(root,old.get("result"),receipt.get("result_manifest"));
            // Fully validated old material precedes an ordinary idempotency conflict.
            if(!hash.equals(command.hash())||!frame.identity().inputHash().equals(command.inputHash()))throw new Rejected("identity_conflict");
            return new Resolved(operation(key),root,attempt,expected+1,text(old,"external_state"),receipt.get("root_result_retained").booleanValue());
        }catch(Rejected conflict){throw conflict;}catch(RuntimeException corrupt){throw unavailable();}
    }

    public Resolved resolve(String key,Command command,Instant now,Verifier verifier){
        JdbcFrozenInputs.requireTx();Objects.requireNonNull(command);Objects.requireNonNull(verifier);if(now==null||now.getNano()%1000!=0)throw unavailable();var evidence=body(command.canonical());String root=text(evidence,"root_id"),attempt=text(evidence,"attempt_id");long expected=WireValues.nonnegativeInteger(evidence.get("expected_revision"));
        var headers=sql.query("SELECT id,revision,state,result_hash,result_json IS NOT NULL,applied_at IS NOT NULL,application_state FROM jobs WHERE id=? AND record_kind='logical_task' FOR UPDATE",(r,n)->new Object[]{r.getString(1),r.getObject(2,Long.class),r.getString(3),r.getBytes(4),r.getBoolean(5),r.getBoolean(6),r.getString(7)},root);
        if(headers.isEmpty()||!root.equals(headers.getFirst()[0]))throw new Rejected("not_found");if(headers.size()!=1)throw unavailable();var header=headers.getFirst();
        if(header[1]==null||(long)header[1]!=expected)throw new Rejected("revision_conflict");
        var locked=sql.query("SELECT id,revision,CASE WHEN OCTET_LENGTH(usage_json) BETWEEN 1 AND 16384 THEN usage_json ELSE NULL END,OCTET_LENGTH(usage_json),cost_decimal,result_hash,result_json IS NOT NULL FROM jobs WHERE id=? AND record_kind='ai_attempt' AND root_job_id=? FOR UPDATE",(r,n)->new Object[]{r.getString(1),r.getObject(2,Long.class),r.getString(3),r.getObject(4,Long.class),r.getBigDecimal(5),r.getBytes(6),r.getBoolean(7)},attempt,root);
        if(locked.isEmpty()||!attempt.equals(locked.getFirst()[0]))throw new Rejected("not_found");if(locked.size()!=1)throw unavailable();var old=locked.getFirst();
        var frame=originals.read(root,attempt);if(frame==null)throw new Rejected("not_found");binds(frame,root,attempt,command.inputHash());
        if(!"unknown_external".equals(frame.attemptState()))throw new Rejected("run_frozen");
        if(old[1]==null||(long)old[1]<0||(long)old[1]>=CanonicalJson.MAX_SAFE_INTEGER||old[5]!=null||(boolean)old[6]||old[3]!=null&&(old[2]==null||(long)old[3]>16384))throw unavailable();
        verifier.verify(frame,command.canonical());String external=text(evidence,"external_state");var usage=mergeUsage((String)old[2],(BigDecimal)old[4],evidence.get("usage"));
        boolean latest=frame.attemptNo()==frame.root().callCount(),pending=latest&&"unknown_external".equals(header[2]);
        if(pending&&(header[3]!=null||(boolean)header[4]||(boolean)header[5]||"APPLIED".equals(header[6])))throw unavailable();
        String state=(String)header[2],application=(String)header[6];boolean retain=pending&&"succeeded".equals(external);
        if(retain){state="result_conflict";application="EXTERNAL_RESULT_CONFIRMED";}else if(pending&&"failed".equals(external)){state="failed";application=null;}
        JsonNode resultManifest=writeResult(root,evidence.get("result"),now);JsonNode review=writeReview(root,attempt,command.inputHash(),command.hash(),command.canonical(),now);
        String nextAttempt="unknown".equals(external)?"unknown_external":external;
        int changed=sql.update(c->{var q=c.prepareStatement("UPDATE jobs SET state=?,usage_json=?,cost_decimal=?,result_hash=?,result_json=?,revision=revision+1,updated_at=? WHERE id=? AND record_kind='ai_attempt' AND root_job_id=? AND state='unknown_external' AND revision=?");q.setString(1,nextAttempt);q.setString(2,usage.json());q.setBigDecimal(3,usage.cost());q.setBytes(4,resultManifest==null?null:HexFormat.of().parseHex(text(resultManifest,"result_hash")));q.setString(5,resultManifest==null?null:JdbcFrozenInputs.canonical(resultManifest));q.setTimestamp(6,Timestamp.from(now),utc());q.setString(7,attempt);q.setString(8,root);q.setLong(9,(long)old[1]);return q;});if(changed!=1)throw new Rejected("revision_conflict");
        final String nextState=state,nextApplication=application;
        changed=sql.update(c->{var q=c.prepareStatement("UPDATE jobs SET state=?,application_state=?,result_hash=CASE WHEN ? THEN ? ELSE result_hash END,result_json=CASE WHEN ? THEN ? ELSE result_json END,error_code=CASE WHEN ? THEN NULL ELSE error_code END,revision=revision+1,updated_at=? WHERE id=? AND record_kind='logical_task' AND revision=?");q.setString(1,nextState);q.setString(2,nextApplication);q.setBoolean(3,retain);q.setBytes(4,retain?HexFormat.of().parseHex(text(resultManifest,"result_hash")):null);q.setBoolean(5,retain);q.setString(6,retain?JdbcFrozenInputs.canonical(resultManifest):null);q.setBoolean(7,pending&&!"unknown".equals(external));q.setTimestamp(8,Timestamp.from(now),utc());q.setString(9,root);q.setLong(10,expected);return q;});if(changed!=1)throw new Rejected("revision_conflict");
        var target=JsonMapper.shared().createObjectNode().put("root_id",root).put("attempt_id",attempt).put("expected_revision",expected);var receipt=JsonMapper.shared().createObjectNode().put("schema_version","owner-external-resolution-receipt-v1").put("root_id",root).put("attempt_id",attempt).put("input_hash",command.inputHash()).put("action_hash",command.hash()).put("expected_revision",expected).put("revision",expected+1).put("external_state",external).put("root_result_retained",retain);receipt.set("review_manifest",review);if(resultManifest==null)receipt.putNull("result_manifest");else receipt.set("result_manifest",resultManifest);
        records.appendDispositionUtc(new JdbcJobRecords.Disposition(operation(key),key,"job_external_resolution",JdbcFrozenInputs.canonical(target),text(evidence,"operator_id"),text(evidence,"reason"),JdbcFrozenInputs.canonical(receipt)),now);
        return new Resolved(operation(key),root,attempt,expected+1,external,retain);
    }

    private record Usage(String json,BigDecimal cost){ }
    private static Usage mergeUsage(String oldJson,BigDecimal oldCost,JsonNode next){
        var old=oldJson==null?JsonMapper.shared().createObjectNode():CanonicalJson.parse(oldJson);if(!old.isObject()||oldCost!=null&&(oldCost.signum()<0||oldCost.compareTo(new BigDecimal("1000000000000"))>=0))throw unavailable();
        var merged=(ObjectNode)old.deepCopy();Long input=known(old,"input_tokens","prompt_tokens"),output=known(old,"output_tokens","completion_tokens");BigDecimal cost=oldCost;
        if(next!=null&&!next.isNull()){
            closed(next,"input_tokens","output_tokens","cost");input=merge(input,nullableToken(next.get("input_tokens")));output=merge(output,nullableToken(next.get("output_tokens")));
            if(!next.get("cost").isNull()){String raw=WireValues.text(next.get("cost"));if(!raw.matches("-?(0|[1-9][0-9]*)(\\.[0-9]{1,8})?"))throw new Rejected("invalid_evidence");BigDecimal incoming=new BigDecimal(raw);if(incoming.signum()<0||incoming.compareTo(new BigDecimal("1000000000000"))>=0)throw new Rejected("invalid_evidence");if(cost!=null&&cost.compareTo(incoming)!=0)throw new Rejected("identity_conflict");if(cost==null)cost=incoming;}
        }
        if(oldJson==null&&input==null&&output==null&&cost==null)return new Usage(null,null);
        merged.remove("input_tokens");merged.remove("output_tokens");if(input==null)merged.putNull("prompt_tokens");else merged.put("prompt_tokens",input);if(output==null)merged.putNull("completion_tokens");else merged.put("completion_tokens",output);merged.put("state",input==null&&output==null?"unknown":"reported_late");return new Usage(JdbcFrozenInputs.canonical(merged),cost);
    }
    private static Long nullableToken(JsonNode value){return value==null||value.isNull()?null:WireValues.nonnegativeInteger(value);}
    private static Long known(JsonNode value,String a,String b){Long first=nullableToken(value.get(a)),second=nullableToken(value.get(b));if(first!=null&&second!=null&&!first.equals(second))throw unavailable();return first==null?second:first;}
    private static Long merge(Long old,Long incoming){if(old!=null&&incoming!=null&&!old.equals(incoming))throw new Rejected("identity_conflict");return old==null?incoming:old;}
    private JsonNode writeResult(String root,JsonNode envelope,Instant now){
        if(envelope==null||envelope.isNull())return null;String hash=WireValues.sha256(envelope.get("result_hash")),payload=JdbcFrozenInputs.canonical(envelope);if(payload.getBytes(StandardCharsets.UTF_8).length>262144)throw new Rejected("invalid_evidence");String part=JdbcFrozenInputs.stableId("retained-result",root+":"+hash);records.putPart(new JdbcJobRecords.Part(part,root,"result",hash,0,payload,CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,envelope)),now);return JsonMapper.shared().createObjectNode().put("schema_version","retained-ai-result-manifest-v1").put("part_id",part).put("result_hash",hash);
    }
    private void verifyResult(String root,JsonNode envelope,JsonNode manifest){
        if(envelope==null||envelope.isNull()){if(manifest==null||!manifest.isNull())throw unavailable();return;}closed(manifest,"schema_version","part_id","result_hash");String hash=WireValues.sha256(envelope.get("result_hash")),id=JdbcFrozenInputs.stableId("retained-result",root+":"+hash);
        if(!"retained-ai-result-manifest-v1".equals(text(manifest,"schema_version"))||!id.equals(text(manifest,"part_id"))||!hash.equals(text(manifest,"result_hash"))||!JdbcFrozenInputs.canonical(envelope).equals(part(id,root,"result",hash,0,null)))throw unavailable();
    }
    private JsonNode writeReview(String root,String attempt,String input,String hash,String evidence,Instant now){
        byte[] bytes=evidence.getBytes(StandardCharsets.UTF_8);var hashes=JsonMapper.shared().createArrayNode();int count=(bytes.length+184319)/184320;if(count<1||count>6)throw unavailable();
        for(int n=0;n<count;n++){var fragment=JsonMapper.shared().createObjectNode().put("schema_version","owner-external-review-fragment-v1").put("root_id",root).put("action_hash",hash).put("part_no",n).put("data",Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes,n*184320,Math.min(bytes.length,(n+1)*184320))));String digest=CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,fragment);hashes.add(digest);records.putPart(new JdbcJobRecords.Part(reviewPart(root,hash,n+1),root,"review",hash,n+1,JdbcFrozenInputs.canonical(fragment),digest),now);}
        var index=JsonMapper.shared().createObjectNode().put("schema_version","owner-external-review-index-v1").put("root_id",root).put("attempt_id",attempt).put("input_hash",input).put("action_hash",hash).put("canonical_bytes",bytes.length);index.set("fragment_hashes",hashes);String digest=CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,index),id=reviewPart(root,hash,0);records.putPart(new JdbcJobRecords.Part(id,root,"review",hash,0,JdbcFrozenInputs.canonical(index),digest),now);return JsonMapper.shared().createObjectNode().put("part_id",id).put("payload_hash",digest);
    }
    private String readReview(String root,String attempt,String input,String hash,JsonNode manifest){
        closed(manifest,"part_id","payload_hash");String id=reviewPart(root,hash,0);if(!id.equals(text(manifest,"part_id")))throw unavailable();var index=CanonicalJson.parse(part(id,root,"review",hash,0,WireValues.sha256(manifest.get("payload_hash"))));closed(index,"schema_version","root_id","attempt_id","input_hash","action_hash","canonical_bytes","fragment_hashes");
        if(!"owner-external-review-index-v1".equals(text(index,"schema_version"))||!root.equals(text(index,"root_id"))||!attempt.equals(text(index,"attempt_id"))||!input.equals(text(index,"input_hash"))||!hash.equals(text(index,"action_hash")))throw unavailable();var hashes=index.get("fragment_hashes");long size=WireValues.nonnegativeInteger(index.get("canonical_bytes"));if(size<1||size>1048576||!hashes.isArray()||hashes.size()!=(size+184319)/184320||hashes.size()>6)throw unavailable();
        Long count=sql.queryForObject("SELECT COUNT(*) FROM jobs WHERE record_kind='snapshot_part' AND owner_job_id=? AND purpose='review' AND stamp_id=?",Long.class,root,hash);if(count==null||count!=hashes.size()+1)throw unavailable();var output=new java.io.ByteArrayOutputStream((int)size);
        for(int n=0;n<hashes.size();n++){var fragment=CanonicalJson.parse(part(reviewPart(root,hash,n+1),root,"review",hash,n+1,WireValues.sha256(hashes.get(n))));closed(fragment,"schema_version","root_id","action_hash","part_no","data");if(!"owner-external-review-fragment-v1".equals(text(fragment,"schema_version"))||!root.equals(text(fragment,"root_id"))||!hash.equals(text(fragment,"action_hash"))||n!=WireValues.nonnegativeInteger(fragment.get("part_no")))throw unavailable();byte[] bytes=Base64.getDecoder().decode(text(fragment,"data"));if(bytes.length!=(n==hashes.size()-1?size-184320L*n:184320))throw unavailable();output.writeBytes(bytes);}
        byte[] bytes=output.toByteArray();String value=new String(bytes,StandardCharsets.UTF_8);if(!Arrays.equals(bytes,value.getBytes(StandardCharsets.UTF_8))||!hash.equals(CanonicalJson.hash(CanonicalJson.Domain.ACTION,CanonicalJson.parse(value)))||!value.equals(JdbcFrozenInputs.canonical(body(value))))throw unavailable();return value;
    }
    private String part(String id,String root,String purpose,String stamp,long number,String expectedHash){
        var rows=sql.query("SELECT id,record_kind,owner_job_id,purpose,stamp_id,part_no,CASE WHEN OCTET_LENGTH(payload_json) BETWEEN 1 AND 262144 THEN payload_json ELSE NULL END,payload_hash,call_count,max_attempts,priority_no,state,revision,record_identity_hash FROM jobs WHERE id=?",(r,n)->new Object[]{r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getObject(6,Long.class),r.getString(7),r.getBytes(8),r.getObject(9,Integer.class),r.getObject(10,Integer.class),r.getObject(11,Integer.class),r.getString(12),r.getObject(13,Long.class),r.getBytes(14)},id);
        if(rows.size()!=1)throw unavailable();var row=rows.getFirst();if(!id.equals(row[0])||!"snapshot_part".equals(row[1])||!root.equals(row[2])||!purpose.equals(row[3])||!stamp.equals(row[4])||!Long.valueOf(number).equals(row[5])||row[6]==null||!Integer.valueOf(0).equals(row[8])||row[9]!=null||!Integer.valueOf(0).equals(row[10])||!"frozen".equals(row[11])||!Long.valueOf(0).equals(row[12])||!Arrays.equals((byte[])row[13],identity("snapshot_part",root+"\u0000"+purpose+"\u0000"+stamp+"\u0000"+number)))throw unavailable();var value=CanonicalJson.parse((String)row[6]);String hash=CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,value);if(!Arrays.equals((byte[])row[7],HexFormat.of().parseHex(hash))||expectedHash!=null&&!expectedHash.equals(hash))throw unavailable();return JdbcFrozenInputs.canonical(value);
    }
    private static JsonNode body(String raw){
        if(raw==null||raw.getBytes(StandardCharsets.UTF_8).length>1048576)throw new IllegalArgumentException("external evidence capacity exceeded");var value=CanonicalJson.parse(raw);closed(value,"schema_version","root_id","attempt_id","operator_id","expected_revision","external_state","reason","evidence_refs","result","usage");
        if(!"owner-external-resolution-evidence-v1".equals(text(value,"schema_version")))throw unavailable();for(String field:List.of("root_id","attempt_id","operator_id"))WireValues.id(value.get(field),false);if(WireValues.nonnegativeInteger(value.get("expected_revision"))>=CanonicalJson.MAX_SAFE_INTEGER||!Set.of("succeeded","failed","unknown").contains(text(value,"external_state")))throw unavailable();
        String reason=text(value,"reason");if(reason.isBlank()||reason.codePointCount(0,reason.length())>2048)throw unavailable();var refs=value.get("evidence_refs");if(!refs.isArray()||refs.isEmpty()||refs.size()>100)throw unavailable();var ids=new HashSet<String>();for(var ref:refs)if(!ids.add(WireValues.id(ref,false)))throw unavailable();if("succeeded".equals(text(value,"external_state"))==value.get("result").isNull())throw unavailable();return value;
    }
    private static void binds(JdbcOwnerExternalAttemptReads.Frame frame,String root,String attempt,String hash){if(frame==null||!root.equals(frame.root().id())||!root.equals(frame.identity().rootId())||!attempt.equals(frame.identity().attemptId())||!hash.equals(frame.identity().inputHash()))throw unavailable();}
    private static String reviewPart(String root,String hash,int part){return JdbcFrozenInputs.stableId("owner-external-review",root+":"+hash+":"+part);}
    private static String operation(String key){return JdbcFrozenInputs.stableId("owner-job-external",key);}
    private static String text(JsonNode value,String field){return WireValues.text(value.get(field));}
    private static void closed(JsonNode value,String...fields){var set=Set.of(fields);WireValues.object(value,set,set);}
    private static byte[] identity(String kind,String key){var value=JsonMapper.shared().createObjectNode().put("record_kind",kind).put("natural_key",key);return HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.IDENTITY,value));}
    private static byte[] digest(String value){return HexFormat.of().parseHex(JdbcResultState.sha(value));}
    private static Calendar utc(){return Calendar.getInstance(TimeZone.getTimeZone("UTC"));}
    private static IllegalStateException unavailable(){return new IllegalStateException("original external resolution ledger unavailable");}
}
