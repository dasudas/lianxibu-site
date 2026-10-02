package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.content.*;
import com.lianxibu.site.service.jobs.VersionValidityService.*;
import java.time.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Permanent validity + bounded immutable direct-reference scan, all under the shared writer. */
public final class VersionValidityJdbcStore implements Store {
    public record DisplayCommand(JsonNode target,long expectedRevision,String reason){
        public DisplayCommand{target=target==null?null:target.deepCopy();if(target==null||!"content_version".equals(target.path("kind").asText())||expectedRevision<1||expectedRevision>=CanonicalJson.MAX_SAFE_INTEGER||reason==null||reason.isBlank()||reason.codePointCount(0,reason.length())>2048)throw new IllegalArgumentException("invalid display command");ContentVersionKey.fromJson(target);}
        @Override public JsonNode target(){return target.deepCopy();}
    }
    private final VersionValidityLedgerService ledger;
    private final JdbcValidityPropagation queue;
    private final FrozenContentVersionService versions;
    private final ContentReadService contents;
    private final ContentReviewWriteService writer;
    private final Clock clock;private final String actor;private final DisplayCommand display;
    private JdbcValidityPropagation.Work lockedWork;
    private String appendedId;private JsonNode appendedTarget;
    public VersionValidityJdbcStore(VersionValidityLedgerService ledger,JdbcValidityPropagation queue,FrozenContentVersionService versions,ContentReadService contents,ContentReviewWriteService writer,Clock clock,String actor,DisplayCommand display){this.ledger=Objects.requireNonNull(ledger);this.queue=Objects.requireNonNull(queue);this.versions=Objects.requireNonNull(versions);this.contents=Objects.requireNonNull(contents);this.writer=Objects.requireNonNull(writer);this.clock=Objects.requireNonNull(clock);this.actor=WireValues.id(actor,false);this.display=display;}
    @Override public Result committed(String key,String hash){return ledger.committed(key,hash);}
    @Override public void lockStableIdentity(JsonNode target){ledger.lockStableIdentity(target);}
    @Override public boolean exactVersionExists(JsonNode target){return ledger.exactVersionExists(target);}
    @Override public Validity validity(JsonNode target){return ledger.validity(target);}
    @Override public String append(Command command,String hash,long next,String key){appendedId=ledger.append(command,hash,next,key);appendedTarget=command.target();return appendedId;}
    @Override public void closeCurrentVersionIfExact(JsonNode target){JdbcFrozenInputs.requireTx();if(appendedId==null||!target.equals(appendedTarget))throw unavailable();if(target.path("kind").asText().equals("content_version"))writer.closeIfCurrent(ContentVersionKey.fromJson(target),null,null,appendedId,false);}
    @Override public void enqueue(String disposition,JsonNode target){VersionValidityService.canonicalTarget(target);queue.enqueue(disposition,target,now());}
    @Override public void withdrawDisplay(JsonNode target){requireDisplay(target);var key=ContentVersionKey.fromJson(target);var current=writer.reviewSnapshot(key.contentKind(),key.contentId());if(current==null||current.revision()!=display.expectedRevision())throw new Rejected("revision_conflict");if(!same(current,key))throw new Rejected("content_version_conflict");writer.closeIfCurrent(key,null,null,null,true);}
    @Override public Boolean committedDisplay(String key,JsonNode target){requireDisplay(target);return queue.receipt(key,"content_display",actor,target,displayHash());}
    @Override public void recordDisplay(String key,JsonNode target){requireDisplay(target);queue.receipt(key,"content_display",actor,display.reason(),target,displayHash(),true,now());}
    @Override public Boolean committedPropagation(String key){return queue.receipt(key,"validity_step",actor,stepTarget(),stepHash());}
    @Override public void recordPropagation(String key,boolean worked){queue.receipt(key,"validity_step",actor,"Bounded exact-version validity propagation",stepTarget(),stepHash(),worked,now());}
    @Override public Work nextWork(){lockedWork=queue.next();return lockedWork==null?null:new Work(lockedWork.disposition(),lockedWork.target(),lockedWork.cursor());}
    @Override public Page directDependents(JsonNode target,String cursor,int limit){
        String full=VersionValidityService.canonicalTarget(target);if(limit<1||limit>100)throw unavailable();var batch=queue.candidates(cursor,limit+1);var dependents=new LinkedHashMap<String,Dependent>();int count=Math.min(limit,batch.size());
        for(int index=0;index<count;index++){var candidate=batch.get(index);var payload=CanonicalJson.parse(candidate.payload());var original=CanonicalJson.parse(candidate.target());
            if(!Arrays.equals(candidate.payloadHash(),hash(JdbcFrozenInputs.canonical(payload)))||!Arrays.equals(candidate.targetHash(),hash(JdbcFrozenInputs.canonical(original))))throw unavailable();
            JsonNode node;
            if(candidate.type().equals("content_review_version")){if(!"content-version-evidence-v1".equals(payload.path("schema_version").asText()))throw unavailable();node=original;}
            else {if(!"result-application-receipt-v1".equals(payload.path("schema_version").asText()))throw unavailable();if(!"APPLIED".equals(payload.path("status").asText()))continue;node=payload.get("content_version");if(node==null||!node.path("input_job_id").asText().equals(original.path("root_id").asText()))throw unavailable();}
            var version=ContentVersionKey.fromJson(node);var proven=versions.find(version).orElseThrow(VersionValidityJdbcStore::unavailable);if(!proven.context().referencesComplete())throw unavailable();
            boolean match=proven.context().referenceTargets().stream().anyMatch(ref->full.equals(VersionValidityService.canonicalTarget(ref)));if(!match)continue;
            var current=contents.reviewSnapshot(version.contentKind(),version.contentId()).orElse(null);long management=1;String stamp="0".repeat(64);
            // Only the CAS metadata comes from the current row; edges always come from the immutable owner's input.
            if(current!=null&&same(current,version)&&current.reviewStampHash()!=null){management=current.revision();stamp=HexFormat.of().formatHex(current.reviewStampHash());}
            dependents.putIfAbsent(VersionValidityService.canonicalTarget(node),new Dependent(node,management,version.inputJobId(),stamp));
        }
        String next=batch.size()>limit?batch.get(count-1).id():null;return new Page(List.copyOf(dependents.values()),next);
    }
    @Override public boolean blockIfCurrent(Dependent dependent,String disposition){
        var key=ContentVersionKey.fromJson(dependent.versionKey());if(!Objects.equals(key.inputJobId(),dependent.inputJobId())||!dependent.reviewStampHash().matches("[0-9a-f]{64}"))throw unavailable();
        var row=contents.reviewSnapshot(key.contentKind(),key.contentId()).orElse(null);
        if(row==null||!same(row,key)||row.revision()!=dependent.contentRevision()||row.reviewStampHash()==null||!HexFormat.of().formatHex(row.reviewStampHash()).equals(dependent.reviewStampHash()))return false;
        // A reversal followed by a new valid review must survive an older queued invalidation.
        if(stillReviewed(row,key))return false;
        return writer.closeIfCurrent(key,dependent.contentRevision(),dependent.reviewStampHash(),disposition,false);
    }
    @Override public void advance(Work work,String cursor){requireWork(work);queue.finish(lockedWork,cursor,now());lockedWork=null;}
    @Override public void complete(Work work){requireWork(work);queue.finish(lockedWork,null,now());lockedWork=null;}
    private void requireWork(Work work){if(lockedWork==null||!lockedWork.disposition().equals(work.dispositionId())||!lockedWork.target().equals(work.target())||!Objects.equals(lockedWork.cursor(),work.cursor()))throw unavailable();}
    private void requireDisplay(JsonNode target){if(display==null||!display.target().equals(target))throw new Rejected("display_command_conflict");}
    private boolean stillReviewed(ReviewContentState row,ContentVersionKey key){
        var proven=versions.find(key).orElseThrow(VersionValidityJdbcStore::unavailable);
        var closure=new FrozenReferenceClosure(new FrozenReferenceClosure.Source(){public FrozenReferenceClosure.Evidence resolve(ContentVersionKey target){return versions.resolve(target);}public boolean leafExists(JsonNode target){return ledger.exactVersionExists(target);}});
        var targets=closure.expand(key,proven.context()).targets();var dependencies=new ArrayList<ReviewPublicationService.Dependency>();for(var target:targets){var validity=ledger.validity(target);dependencies.add(new ReviewPublicationService.Dependency(target,validity.revision(),validity.effectiveValid()));}
        var current=new ReviewPublicationService.Current(row,new ReviewPublicationService.Frozen(row.inputJobId(),HexFormat.of().parseHex(proven.inputHash()),targets,true),dependencies,ledger.validity(CanonicalJson.parse(JdbcFrozenContentVersions.version(key))).effectiveValid());
        return ReviewPublicationService.matchesQualification(row,current);
    }
    private String displayHash(){var node=JsonMapper.shared().createObjectNode();node.put("operation","display_adjustment");node.set("target",display.target());node.put("expected_revision",display.expectedRevision());node.put("reason",display.reason());return CanonicalJson.hash(CanonicalJson.Domain.ACTION,node);}
    private static boolean same(ReviewContentState row,ContentVersionKey version){return row.bodyRevision()!=null&&row.bodyRevision()==version.revision()&&row.bodyHashV1()!=null&&version.bodyHash().equals(HexFormat.of().formatHex(row.bodyHashV1()))&&Objects.equals(row.inputJobId(),version.inputJobId());}
    private Instant now(){return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);}
    private static JsonNode stepTarget(){return CanonicalJson.parse("{\"kind\":\"validity_propagation\"}");}
    private static String stepHash(){return CanonicalJson.hash(CanonicalJson.Domain.ACTION,stepTarget());}
    private static byte[] hash(String value){return HexFormat.of().parseHex(JdbcResultState.sha(value));}
    private static IllegalStateException unavailable(){return new IllegalStateException("exact frozen validity propagation unavailable");}
}
