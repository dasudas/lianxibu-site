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

/** Per-command immutable context; service owns the shared transaction. A restarted
 * instance reconstructs receipts and committed dependency proofs from jobs. */
public final class GenerationJdbcStore implements FrozenStore {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final JdbcJobRecords records;
    private final JdbcFrozenInputs inputs;
    private final GenerationLineageService lineage;
    private final Clock clock;
    private final Prepare command;
    private final CollectionProofSource collections;
    private final SourceDedupDependencyService sourceDedup;
    private final SourceDedupPartialDependencyPermitService.Captured partialPermit;
    private final String actor,owner,commandHash;
    // Pure structure only: actual authority is rechecked below on every call.
    private volatile String verifiedStructuralValueInput;
    public GenerationJdbcStore(DataSource source,Clock clock,String actor,String owner,Prepare command) {
        this(source,clock,actor,owner,command,null);
    }
    public GenerationJdbcStore(DataSource source,Clock clock,String actor,String owner,Prepare command,CollectionProofSource collections) {
        this(source,clock,actor,owner,command,collections,null);
    }
    public GenerationJdbcStore(DataSource source,Clock clock,String actor,String owner,Prepare command,
            CollectionProofSource collections,GenerationLineageService.BirthSource births) {this(source,clock,actor,owner,command,collections,births,null,null);}
    /** Explicit partial authority; readonly recovery uses actual proof and every writer needs its opaque capture. */
    public GenerationJdbcStore(DataSource source,Clock clock,String actor,String owner,Prepare command,
            CollectionProofSource collections,GenerationLineageService.BirthSource births,SourceDedupDependencyService sourceDedup,
            SourceDedupPartialDependencyPermitService.Captured partialPermit) {
        this.jdbc=new JdbcTemplate(Objects.requireNonNull(source));this.records=new JdbcJobRecords(source);this.inputs=new JdbcFrozenInputs(source);
        this.clock=Objects.requireNonNull(clock);this.command=immutable(Objects.requireNonNull(command));
        this.collections=collections;
        this.sourceDedup=sourceDedup==null?new SourceDedupDependencyService(new com.lianxibu.site.repository.jobs.JdbcSourceStageAvailability(source)):sourceDedup;this.partialPermit=partialPermit;
        this.actor=WireValues.id(actor,false);this.owner=WireValues.id(owner,false);
        this.lineage=new GenerationLineageService(new com.lianxibu.site.repository.jobs.JdbcGenerationLineage(source),clock,this.actor,births);
        commandHash=CanonicalJson.hash(CanonicalJson.Domain.ACTION,commandNode(this.command));
    }
    @Override public Frozen committed(String actionKey) {
        key(actionKey);
        var rows=jdbc.query("SELECT id,record_kind,target_type,CASE WHEN OCTET_LENGTH(target_key_json) BETWEEN 1 AND 4096 THEN target_key_json ELSE NULL END,target_key_hash,operator_id,reason,CASE WHEN OCTET_LENGTH(action_payload) BETWEEN 1 AND 65536 THEN action_payload ELSE NULL END,action_payload_hash,action_key_hash,acted_at,OCTET_LENGTH(target_key_json),OCTET_LENGTH(action_payload) FROM jobs WHERE id=? OR action_key_hash=? LIMIT 3",(rs,n)->{
            Long targetBytes=rs.getObject(12,Long.class),payloadBytes=rs.getObject(13,Long.class);if(targetBytes==null||targetBytes<1||targetBytes>4096||payloadBytes==null||payloadBytes<1||payloadBytes>65536)throw conflict();
            String target=rs.getString(4),payload=rs.getString(8);if(!JdbcFrozenInputs.stableId("prepare-action",actionKey).equals(rs.getString(1))||!"disposition".equals(rs.getString(2))||!"generation_prepare".equals(rs.getString(3))||!actor.equals(rs.getString(6))||!"Frozen generation input and prepared request".equals(rs.getString(7))||rs.getTimestamp(11)==null||!Arrays.equals(rs.getBytes(5),sha(JdbcFrozenInputs.canonical(CanonicalJson.parse(target))))||!Arrays.equals(rs.getBytes(9),sha(JdbcFrozenInputs.canonical(CanonicalJson.parse(payload))))||!Arrays.equals(rs.getBytes(10),sha(actionKey)))throw conflict();
            var targetNode=CanonicalJson.parse(target);var fields=Set.of("kind","id");WireValues.object(targetNode,fields,fields);if(!"logical_task".equals(WireValues.text(targetNode.get("kind")))||!owner.equals(WireValues.text(targetNode.get("id"))))throw conflict();return CanonicalJson.parse(payload);
        },JdbcFrozenInputs.stableId("prepare-action",actionKey),sha(actionKey));
        if(rows.isEmpty())return null;
        if(rows.size()!=1)throw conflict();var payload=rows.getFirst();var fields=Set.of("schema_version","command_hash","owner_id","cutoff","coverage_state","missing","input_hash","attempt_id","request_hash","eligible_at","state");WireValues.object(payload,fields,fields);
        if(!"generation-prepare-receipt-v1".equals(WireValues.text(payload.get("schema_version")))||!commandHash.equals(WireValues.text(payload.get("command_hash")))||!owner.equals(WireValues.text(payload.get("owner_id"))))throw conflict();
        lineage.read(owner);
        String hash=nullable(payload,"input_hash"),attemptId=nullable(payload,"attempt_id"),requestHash=nullable(payload,"request_hash");
        if(hash!=null&&!hash.matches("[0-9a-f]{64}")||requestHash!=null&&!requestHash.matches("[0-9a-f]{64}")||(attemptId==null)!=(requestHash==null)||attemptId!=null&&(hash==null||!attemptId.equals(JdbcFrozenInputs.stableId("attempt",owner+":1"))))throw conflict();
        String snapshot=hash==null?null:inputs.read(owner,hash),request=null;
        if(snapshot!=null)sourceValueProof(CanonicalJson.parse(snapshot));
        if(attemptId!=null){var attempt=records.byId(attemptId);if(attempt==null||!"ai_attempt".equals(attempt.kind())||!owner.equals(attempt.rootId())||!Integer.valueOf(1).equals(attempt.attemptNo())||attempt.requestJson()==null)throw conflict();request=JdbcFrozenInputs.canonical(CanonicalJson.parse(attempt.requestJson()));if(!CanonicalJson.hash(CanonicalJson.Domain.ACTION,CanonicalJson.parse(request)).equals(requestHash)||!hash.equals(CanonicalJson.parse(request).path("input_hash").asText())||!jdbc.queryForObject("SELECT snapshot_owner_id=? AND input_hash=? FROM jobs WHERE id=?",Boolean.class,owner,HexFormat.of().parseHex(hash),attemptId))throw conflict();}
        var missing=new ArrayList<String>();var omissions=payload.get("missing");if(!omissions.isArray()||omissions.size()>10000)throw conflict();String previous=null;
        for(var m:omissions){String item=WireValues.text(m);if(item.isBlank()||previous!=null&&previous.compareTo(item)>=0)throw conflict();missing.add(item);previous=item;}
        String coverage=WireValues.text(payload.get("coverage_state")),state=WireValues.text(payload.get("state"));
        if(!Set.of("waiting_input","queued","no_input","coverage_only","no_updates").contains(state)||"queued".equals(state)!=(request!=null))throw conflict();
        if("waiting_input".equals(state)){if(!"pending".equals(coverage)||!missing.isEmpty()||hash!=null||attemptId!=null)throw conflict();}
        else if(command.stage().report()&&snapshot!=null&&CanonicalJson.parse(snapshot).has(com.lianxibu.site.service.content.ReportInputProfile.FIELD)){
            var original=CanonicalJson.parse(snapshot);com.lianxibu.site.service.content.ReportInputProfile.validated(original);
            var profile=original.get(com.lianxibu.site.service.content.ReportInputProfile.FIELD);
            if(!coverage.equals(WireValues.text(original.get("coverage_state")))||!coverage.equals(WireValues.text(profile.get("coverage").get("state"))))throw conflict();
            var originalMissing=new ArrayList<String>();for(var item:original.get("missing"))originalMissing.add(WireValues.text(item));
            String originalState=!original.get("members").isEmpty()?"queued":profile.get("coverage").get("no_update_dates").isEmpty()?"no_input":"coverage_only";
            if(!missing.equals(originalMissing)||!state.equals(originalState))throw conflict();
        }
        else if(!(missing.isEmpty()?"complete":"partial").equals(coverage))throw conflict();
        var value=new Frozen(owner,command.logicalKey(),command.generation(),command.stage(),receiptTime(payload.get("cutoff")),coverage,List.copyOf(missing),snapshot,hash,request,command.stage().priority(),receiptTime(payload.get("eligible_at")),state);validate(value);return value;
    }
    @Override public void save(Frozen value,String actionKey) {
        JdbcFrozenInputs.requireTx();key(actionKey);validate(value);
        var prior=committed(actionKey);if(prior!=null){if(!prior.equals(value))throw conflict();return;}
        CollectionInputProofs.verify(command,collections,false);
        Instant now=clock.instant();String type=command.stage().name().toLowerCase(Locale.ROOT);
        String intent=records.ensureIntent(new JdbcJobRecords.Intent(JdbcFrozenInputs.stableId("intent",command.logicalKey()+":"+command.generation()),command.logicalKey(),command.generation(),type,command.businessDate()),now);
        if(records.byId(owner)!=null)lineage.read(owner);
        String root=records.createRoot(new JdbcJobRecords.Root(owner,intent,command.logicalKey(),command.generation(),command.stagePart(),type,command.stage().priority()),now);
        if(!owner.equals(root)||!"waiting_input".equals(records.byId(root).state()))throw conflict();
        lineage.record(root);
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
        if(jdbc.update("UPDATE jobs SET state=?,input_state=?,coverage_state=?,missing_inputs_json=?,input_cutoff_at=?,eligible_at=?,business_date=?,updated_at=? WHERE id=? AND record_kind='logical_task' AND state='waiting_input' AND call_count=0",value.state(),inputState,value.coverageState(),JdbcFrozenInputs.canonical(missing),Timestamp.from(value.cutoff()),Timestamp.from(value.eligibleAt()),command.businessDate().toString(),Timestamp.from(now),owner)!=1)throw conflict();
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
    /** Pure preparation only. Native permission is always rechecked by save/committed. */
    public void primePureInput(Frozen value) {
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw conflict();
        validateStructure(value);
    }
    private static Prepare immutable(Prepare c) {
        var evidence=c.evidence().stream().map(e->new Evidence(e.id(),e.kind(),e.businessDate(),e.completedAt(),e.bodyHash(),e.bodyJson(),e.effectiveValid(),e.blocked(),e.projectionDecision(),List.copyOf(e.references()))).toList();
        return new Prepare(c.logicalKey(),c.generation(),c.stage(),c.businessDate(),c.sourceId(),List.copyOf(c.dependencies()),evidence,List.copyOf(c.expectedDependencies()),c.explicitSupplement(),c.settingsHash(),c.applicationContext(),List.copyOf(c.collectionProofs()),c.stagePart());
    }
    private void validate(Frozen v) {
        validateStructure(v);
        if(v.snapshotJson()!=null)sourceValueAuthority(CanonicalJson.parse(v.snapshotJson()));
    }
    private void validateStructure(Frozen v) {
        if(command.stagePart()<0||command.stagePart()>=10000||command.stagePart()!=0&&!(command.stage().dedup()||command.stage()==Stage.NEWS_VALUE))throw conflict();
        if(v==null||!owner.equals(v.ownerId())||!command.logicalKey().equals(v.logicalKey())||v.generation()!=command.generation()||v.stage()!=command.stage()||v.priority()!=command.stage().priority()||v.cutoff()==null||v.eligibleAt()==null||v.missing()==null||!Set.of("waiting_input","queued","no_input","coverage_only","no_updates").contains(v.state())||v.cutoff().getNano()%1000!=0||v.eligibleAt().getNano()%1000!=0)throw conflict();
        if((v.snapshotJson()==null)!=(v.inputHash()==null)||v.requestJson()!=null&&v.snapshotJson()==null||"queued".equals(v.state())!=(v.requestJson()!=null))throw conflict();
        if(!command.collectionProofs().isEmpty()&&v.snapshotJson()==null)throw conflict();
        if(v.snapshotJson()!=null){var snapshot=CanonicalJson.parse(v.snapshotJson());if(!command.logicalKey().equals(snapshot.path("logical_key").asText())||snapshot.path("generation").asLong()!=command.generation()||!command.businessDate().toString().equals(snapshot.path("business_date").asText())||!command.stage().name().toLowerCase(Locale.ROOT).equals(snapshot.path("stage").asText())||!command.settingsHash().equals(snapshot.path("settings_hash").asText())||!v.cutoff().equals(Instant.parse(snapshot.path("cutoff").asText()))||!v.coverageState().equals(snapshot.path("coverage_state").asText()))throw conflict();
            sourceValueStructure(snapshot);
            if(snapshot.has("stage_part")?WireValues.nonnegativeInteger(snapshot.get("stage_part"))!=command.stagePart():command.stagePart()!=0)throw conflict();
            if(command.applicationContext()==null?snapshot.has(FrozenApplicationContext.FIELD):!command.applicationContext().json().equals(FrozenApplicationContext.fromJson(snapshot.get(FrozenApplicationContext.FIELD)).json()))throw conflict();
            var manifest=snapshot.get(FrozenDependencyManifest.FIELD);
            FrozenDependencyManifest.read(manifest);
            if(!Arrays.equals(CanonicalJson.bytes(manifest),CanonicalJson.bytes(FrozenDependencyManifest.create(command.dependencies(),command.expectedDependencies()))))throw conflict();
            var collection=snapshot.get(FrozenCollectionManifest.FIELD);
            if(command.collectionProofs().isEmpty()) {if(collection!=null)throw conflict();}
            else {
                FrozenCollectionManifest.read(collection);
                if(!Arrays.equals(CanonicalJson.bytes(collection),CanonicalJson.bytes(FrozenCollectionManifest.create(command.collectionProofs()))))throw conflict();
            }
        }
        if(v.requestJson()!=null){var request=CanonicalJson.parse(v.requestJson());if(!JdbcFrozenInputs.canonical(request).equals(v.requestJson())||v.requestJson().getBytes(StandardCharsets.UTF_8).length>1048576||!v.inputHash().equals(request.path("input_hash").asText()))throw conflict();}
    }
    private void sourceValueProof(JsonNode snapshot) {
        sourceValueStructure(snapshot);sourceValueAuthority(snapshot);
    }
    private void sourceValueStructure(JsonNode snapshot) {
        if(snapshot.has(FrozenSourceDedupDependency.FIELD)||snapshot.has(NewsValueInputProfile.ELIGIBILITY_FIELD)) {
            if(command.stage()!=Stage.NEWS_VALUE)throw conflict();
            String canonical=JdbcFrozenInputs.canonical(snapshot);
            if(!canonical.equals(verifiedStructuralValueInput)){
                NewsValueInputProfile.complete(snapshot);
                // One bounded canonical input; this is never a native permission cache.
                verifiedStructuralValueInput=canonical.getBytes(StandardCharsets.UTF_8).length<=1048576?canonical:null;
            }
        }
    }
    private void sourceValueAuthority(JsonNode snapshot) {
        if(snapshot.has(FrozenSourceDedupDependency.FIELD)||snapshot.has(NewsValueInputProfile.ELIGIBILITY_FIELD)) {
            if(command.stage()!=Stage.NEWS_VALUE)throw conflict();
            var profile=snapshot.get(FrozenSourceDedupDependency.FIELD);Instant cutoff=Instant.parse(WireValues.text(snapshot.get("cutoff")));
            if("source-dedup-dependency-v2".equals(WireValues.text(profile.get("schema_version")))){
                var proven=org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()?sourceDedup.readPartial(profile,cutoff):sourceDedup.readPartial(Objects.requireNonNull(partialPermit),cutoff);
                if(!proven.dependency().json().equals(JdbcFrozenInputs.canonical(profile)))throw conflict();
            }else sourceDedup.read(profile,cutoff);
        }
    }
    private static JsonNode commandNode(Prepare c){var n=JSON.createObjectNode();n.put("logical_key",c.logicalKey());n.put("generation",c.generation());if(c.stagePart()!=0)n.put("stage_part",c.stagePart());n.put("stage",c.stage().name());n.put("business_date",c.businessDate().toString());put(n,"source_id",c.sourceId());n.put("explicit_supplement",c.explicitSupplement());n.put("settings_hash",c.settingsHash());var d=n.putArray("dependencies");c.dependencies().forEach(x->d.add(dependencyNode(x)));var e=n.putArray("evidence");for(var x:c.evidence()){var m=e.addObject();m.put("id",x.id());m.put("kind",x.kind());put(m,"business_date",x.businessDate()==null?null:x.businessDate().toString());put(m,"completed_at",x.completedAt()==null?null:x.completedAt().toString());m.put("body_hash",x.bodyHash());put(m,"body_json",x.bodyJson());m.put("effective_valid",x.effectiveValid());m.put("blocked",x.blocked());put(m,"projection_decision",x.projectionDecision());var refs=m.putArray("references");x.references().forEach(refs::add);}var expected=n.putArray("expected_dependencies");c.expectedDependencies().forEach(expected::add);if(c.applicationContext()==null)n.putNull(FrozenApplicationContext.FIELD);else n.set(FrozenApplicationContext.FIELD,c.applicationContext().toJson());if(!c.collectionProofs().isEmpty())n.set(FrozenCollectionManifest.FIELD,FrozenCollectionManifest.create(c.collectionProofs()));return n;}
    private static JsonNode dependencyNode(Dependency d){var n=JSON.createObjectNode();n.put("id",d.id());n.put("stage",d.stage().name());n.put("state",d.state());put(n,"completed_at",d.completedAt()==null?null:d.completedAt().toString());return n;}
    private static void put(tools.jackson.databind.node.ObjectNode n,String k,String v){if(v==null)n.putNull(k);else n.put(k,v);}
    private static String nullable(JsonNode n,String k){if(!n.has(k))throw conflict();return n.get(k).isNull()?null:WireValues.text(n.get(k));}
    private static Instant receiptTime(JsonNode n){String raw=WireValues.text(n);Instant parsed=Instant.parse(raw);if(!raw.endsWith("Z")||parsed.getNano()%1000!=0||!parsed.toString().equals(raw))throw conflict();return parsed;}
    private static void key(String k){if(k==null||!k.matches("[A-Za-z0-9._:-]{1,128}"))throw conflict();}
    private static byte[] sha(String v){try{return MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8));}catch(Exception e){throw new IllegalStateException(e);}}
    private static IllegalStateException conflict(){return new IllegalStateException("frozen generation identity or persistence proof conflict");}
}
