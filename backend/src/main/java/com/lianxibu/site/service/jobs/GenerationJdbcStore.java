package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import com.lianxibu.site.repository.jobs.JdbcJobRecords;
import com.lianxibu.site.service.jobs.GenerationFlowService.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Per-command immutable context; service owns the shared transaction. No source query
 * or provider call occurs here. A restarted instance reconstructs receipts from jobs. */
public final class GenerationJdbcStore implements FrozenStore {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final JdbcJobRecords records;
    private final JdbcFrozenInputs inputs;
    private final Clock clock;
    private final Prepare command;
    private final String actor,owner,commandHash;
    public GenerationJdbcStore(DataSource source,Clock clock,String actor,String owner,Prepare command) {
        this.jdbc=new JdbcTemplate(Objects.requireNonNull(source));this.records=new JdbcJobRecords(source);this.inputs=new JdbcFrozenInputs(source);
        this.clock=Objects.requireNonNull(clock);this.command=Objects.requireNonNull(command);
        this.actor=WireValues.id(actor,false);this.owner=WireValues.id(owner,false);
        commandHash=CanonicalJson.hash(CanonicalJson.Domain.ACTION,commandNode(command));
    }
    @Override public Frozen committed(String actionKey) {
        key(actionKey);
        var rows=jdbc.query("SELECT target_type,target_key_json,operator_id,action_payload,action_payload_hash FROM jobs WHERE record_kind='disposition' AND action_key_hash=?",(rs,n)->new Object[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getBytes(5)},sha(actionKey));
        if(rows.isEmpty())return null;
        if(rows.size()!=1)throw conflict();var row=rows.getFirst();var payload=CanonicalJson.parse((String)row[3]);
        if(!"generation_prepare".equals(row[0])||!actor.equals(row[2])||!CanonicalJson.parse((String)row[1]).path("id").asText().equals(owner)||!Arrays.equals((byte[])row[4],sha(JdbcFrozenInputs.canonical(payload)))||!"generation-prepare-receipt-v1".equals(payload.path("schema_version").asText())||!commandHash.equals(payload.path("command_hash").asText())||!owner.equals(payload.path("owner_id").asText()))throw conflict();
        String hash=nullable(payload,"input_hash"),attemptId=nullable(payload,"attempt_id");
        String snapshot=hash==null?null:inputs.read(owner,hash),request=null;
        if(attemptId!=null){var attempt=records.byId(attemptId);if(attempt==null||!"ai_attempt".equals(attempt.kind())||!owner.equals(attempt.rootId())||!Integer.valueOf(1).equals(attempt.attemptNo())||attempt.requestJson()==null)throw conflict();request=JdbcFrozenInputs.canonical(CanonicalJson.parse(attempt.requestJson()));if(!CanonicalJson.hash(CanonicalJson.Domain.ACTION,CanonicalJson.parse(request)).equals(payload.path("request_hash").asText())||!hash.equals(CanonicalJson.parse(request).path("input_hash").asText())||!jdbc.queryForObject("SELECT snapshot_owner_id=? AND input_hash=? FROM jobs WHERE id=?",Boolean.class,owner,HexFormat.of().parseHex(hash),attemptId))throw conflict();}
        var missing=new ArrayList<String>();for(var m:payload.path("missing")){if(!m.isTextual())throw conflict();missing.add(m.asText());}
        return new Frozen(owner,command.logicalKey(),command.generation(),command.stage(),Instant.parse(payload.path("cutoff").asText()),payload.path("coverage_state").asText(),List.copyOf(missing),snapshot,hash,request,command.stage().priority(),Instant.parse(payload.path("eligible_at").asText()),payload.path("state").asText());
    }
    @Override public void save(Frozen value,String actionKey) {
        JdbcFrozenInputs.requireTx();key(actionKey);if(!commandHash.equals(CanonicalJson.hash(CanonicalJson.Domain.ACTION,commandNode(command))))throw conflict();validate(value);
        var prior=committed(actionKey);if(prior!=null){if(!prior.equals(value))throw conflict();return;}
        Instant now=clock.instant();String type=command.stage().name().toLowerCase(Locale.ROOT);
        String intent=records.ensureIntent(new JdbcJobRecords.Intent(JdbcFrozenInputs.stableId("intent",command.logicalKey()+":"+command.generation()),command.logicalKey(),command.generation(),type,command.businessDate()),now);
        String root=records.createRoot(new JdbcJobRecords.Root(owner,intent,command.logicalKey(),command.generation(),0,type,command.stage().priority()),now);
        if(!owner.equals(root)||!"waiting_input".equals(records.byId(root).state()))throw conflict();
        String attempt=null;
        if(value.snapshotJson()!=null) {
            inputs.save(owner,value.snapshotJson(),value.inputHash(),now);
            // Index exact known root dependencies. Missing expected IDs stay in coverage;
            // neither title matching nor current content relations manufacture links.
            validateDependencyGraph();var seen=new HashSet<String>();int n=0;
            for(var dep:command.dependencies()) {
                if(!seen.add(dep.id())||owner.equals(dep.id()))throw conflict();
                var upstream=records.byId(dep.id());if(upstream==null||!"logical_task".equals(upstream.kind()))throw conflict();
                var p=dependencyNode(dep);String part=records.putPart(new JdbcJobRecords.Part(JdbcFrozenInputs.stableId("dependency",owner+":"+dep.id()),owner,"dependency","input",n++,JdbcFrozenInputs.canonical(p),CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,p)),now);
                if(jdbc.update("UPDATE jobs SET dependency_id=? WHERE id=? AND record_kind='snapshot_part' AND dependency_id IS NULL",dep.id(),part)!=1)throw conflict();
            }
        }
        if(value.requestJson()!=null) {
            attempt=records.prepareAttempt(new JdbcJobRecords.Attempt(JdbcFrozenInputs.stableId("attempt",owner+":1"),owner,1,value.requestJson(),owner,value.inputHash()),now);
        }
        String inputState=value.requestJson()!=null?"ready":"waiting_input".equals(value.state())?"waiting_input":"no_input";
        var missing=JSON.createArrayNode();value.missing().forEach(missing::add);
        if(jdbc.update("UPDATE jobs SET state=?,input_state=?,coverage_state=?,missing_inputs_json=?,input_cutoff_at=?,eligible_at=?,business_date=?,updated_at=? WHERE id=? AND record_kind='logical_task' AND state='waiting_input' AND call_count=0",value.state(),inputState,value.coverageState(),JdbcFrozenInputs.canonical(missing),Timestamp.from(value.cutoff()),Timestamp.from(value.eligibleAt()),java.sql.Date.valueOf(command.businessDate()),Timestamp.from(now),owner)!=1)throw conflict();
        if(jdbc.update("UPDATE jobs SET state=?,input_state=?,coverage_state=?,input_cutoff_at=?,updated_at=? WHERE id=? AND record_kind='generation_intent'",value.state(),inputState,value.coverageState(),Timestamp.from(value.cutoff()),Timestamp.from(now),intent)!=1)throw conflict();
        var receipt=JSON.createObjectNode();receipt.put("schema_version","generation-prepare-receipt-v1");receipt.put("command_hash",commandHash);receipt.put("owner_id",owner);receipt.put("cutoff",value.cutoff().toString());receipt.put("coverage_state",value.coverageState());receipt.set("missing",missing);put(receipt,"input_hash",value.inputHash());put(receipt,"attempt_id",attempt);put(receipt,"request_hash",value.requestJson()==null?null:CanonicalJson.hash(CanonicalJson.Domain.ACTION,CanonicalJson.parse(value.requestJson())));receipt.put("eligible_at",value.eligibleAt().toString());receipt.put("state",value.state());
        var target=JSON.createObjectNode();target.put("kind","logical_task");target.put("id",owner);
        String payload=JdbcFrozenInputs.canonical(receipt);if(payload.getBytes(StandardCharsets.UTF_8).length>65536)throw conflict();
        records.appendDisposition(new JdbcJobRecords.Disposition(JdbcFrozenInputs.stableId("prepare-action",actionKey),actionKey,"generation_prepare",JdbcFrozenInputs.canonical(target),actor,"Frozen generation input and prepared request",payload),now);
    }
    private void validateDependencyGraph() {
        var todo=new ArrayDeque<String>();command.dependencies().forEach(d->todo.add(d.id()));var visited=new HashSet<String>();
        while(!todo.isEmpty()){String next=todo.removeFirst();if(owner.equals(next))throw conflict();if(!visited.add(next))continue;if(visited.size()>10000)throw conflict();
            jdbc.query("SELECT dependency_id FROM jobs WHERE record_kind='snapshot_part' AND owner_job_id=? AND purpose='dependency' AND dependency_id IS NOT NULL",(rs,n)->rs.getString(1),next).forEach(todo::addLast);
        }
    }
    private void validate(Frozen v) {
        if(v==null||!owner.equals(v.ownerId())||!command.logicalKey().equals(v.logicalKey())||v.generation()!=command.generation()||v.stage()!=command.stage()||v.priority()!=command.stage().priority()||v.cutoff()==null||v.eligibleAt()==null||v.missing()==null||!Set.of("waiting_input","queued","no_input","coverage_only","no_updates").contains(v.state())||v.cutoff().getNano()%1000!=0||v.eligibleAt().getNano()%1000!=0)throw conflict();
        if((v.snapshotJson()==null)!=(v.inputHash()==null)||v.requestJson()!=null&&v.snapshotJson()==null||"queued".equals(v.state())!=(v.requestJson()!=null))throw conflict();
        if(v.snapshotJson()!=null){var snapshot=CanonicalJson.parse(v.snapshotJson());if(!command.logicalKey().equals(snapshot.path("logical_key").asText())||snapshot.path("generation").asLong()!=command.generation()||!command.businessDate().toString().equals(snapshot.path("business_date").asText())||!command.stage().name().toLowerCase(Locale.ROOT).equals(snapshot.path("stage").asText())||!command.settingsHash().equals(snapshot.path("settings_hash").asText())||!v.cutoff().equals(Instant.parse(snapshot.path("cutoff").asText()))||!v.coverageState().equals(snapshot.path("coverage_state").asText()))throw conflict();
            if(command.applicationContext()==null?snapshot.has(FrozenApplicationContext.FIELD):!command.applicationContext().json().equals(FrozenApplicationContext.fromJson(snapshot.get(FrozenApplicationContext.FIELD)).json()))throw conflict();
        }
        if(v.requestJson()!=null){var request=CanonicalJson.parse(v.requestJson());if(!JdbcFrozenInputs.canonical(request).equals(v.requestJson())||v.requestJson().getBytes(StandardCharsets.UTF_8).length>1048576||!v.inputHash().equals(request.path("input_hash").asText()))throw conflict();}
    }
    private static JsonNode commandNode(Prepare c){var n=JSON.createObjectNode();n.put("logical_key",c.logicalKey());n.put("generation",c.generation());n.put("stage",c.stage().name());n.put("business_date",c.businessDate().toString());put(n,"source_id",c.sourceId());n.put("explicit_supplement",c.explicitSupplement());n.put("settings_hash",c.settingsHash());var d=n.putArray("dependencies");c.dependencies().forEach(x->d.add(dependencyNode(x)));var e=n.putArray("evidence");for(var x:c.evidence()){var m=e.addObject();m.put("id",x.id());m.put("kind",x.kind());put(m,"business_date",x.businessDate()==null?null:x.businessDate().toString());put(m,"completed_at",x.completedAt()==null?null:x.completedAt().toString());m.put("body_hash",x.bodyHash());put(m,"body_json",x.bodyJson());m.put("effective_valid",x.effectiveValid());m.put("blocked",x.blocked());put(m,"projection_decision",x.projectionDecision());var refs=m.putArray("references");x.references().forEach(refs::add);}var expected=n.putArray("expected_dependencies");c.expectedDependencies().forEach(expected::add);if(c.applicationContext()==null)n.putNull(FrozenApplicationContext.FIELD);else n.set(FrozenApplicationContext.FIELD,c.applicationContext().toJson());return n;}
    private static JsonNode dependencyNode(Dependency d){var n=JSON.createObjectNode();n.put("id",d.id());n.put("stage",d.stage().name());n.put("state",d.state());put(n,"completed_at",d.completedAt()==null?null:d.completedAt().toString());return n;}
    private static void put(tools.jackson.databind.node.ObjectNode n,String k,String v){if(v==null)n.putNull(k);else n.put(k,v);}
    private static String nullable(JsonNode n,String k){if(!n.has(k))throw conflict();return n.get(k).isNull()?null:n.get(k).asText();}
    private static void key(String k){if(k==null||!k.matches("[A-Za-z0-9._:-]{1,128}"))throw conflict();}
    private static byte[] sha(String v){try{return MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8));}catch(Exception e){throw new IllegalStateException(e);}}
    private static IllegalStateException conflict(){return new IllegalStateException("frozen generation identity or persistence proof conflict");}
}
