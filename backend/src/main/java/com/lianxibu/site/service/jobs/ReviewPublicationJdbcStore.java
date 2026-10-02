package com.lianxibu.site.service.jobs;

import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import com.lianxibu.site.service.content.*;
import com.lianxibu.site.service.content.ReviewPublicationService.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Persistent review orchestration. It requires explicit exact-version validity;
 * no default proof, production registration or HTTP assembly is installed here. */
public final class ReviewPublicationJdbcStore implements Store {
    public interface ValidityProof {
        boolean exactVersionExists(JsonNode target);
        VersionValidityService.Validity validity(JsonNode target);
        void lockStableIdentity(JsonNode target);
    }
    public record Command(String operation,String kind,String id,long expectedRevision,
                          String bodyHash,String reason) {
        public Command {
            if(!Set.of("approve","publish","edit").contains(operation)||!Set.of("report","agent","skill","workflow","model").contains(kind)||expectedRevision<1||expectedRevision>=CanonicalJson.MAX_SAFE_INTEGER||reason==null||reason.isBlank()||reason.codePointCount(0,reason.length())>2048)throw new IllegalArgumentException("invalid review command");WireValues.id(id,false);
            if(operation.equals("edit")?bodyHash==null||!bodyHash.matches("[0-9a-f]{64}"):bodyHash!=null)throw new IllegalArgumentException("invalid review body identity");
        }
    }
    private final Command command;
    private final String commandHash;
    private final ContentReviewWriteService writer;
    private final ContentReadService contents;
    private final ContentReviewEvidenceService audit;
    private final FrozenContentVersionService versions;
    private final ValidityProof validity;
    private final FrozenReferenceClosure closure;
    public ReviewPublicationJdbcStore(Command command,ContentReviewWriteService writer,ContentReadService contents,
                                      ContentReviewEvidenceService audit,FrozenContentVersionService versions,ValidityProof validity){
        this.command=command;this.writer=Objects.requireNonNull(writer);this.contents=Objects.requireNonNull(contents);this.audit=Objects.requireNonNull(audit);this.versions=Objects.requireNonNull(versions);this.validity=Objects.requireNonNull(validity);
        commandHash=command==null?null:hash(command);
        closure=new FrozenReferenceClosure(new FrozenReferenceClosure.Source(){public FrozenReferenceClosure.Evidence resolve(ContentVersionKey key){return versions.resolve(key);}public boolean leafExists(JsonNode target){return validity.exactVersionExists(target);}});
    }
    @Override public void verifyCommand(String operation,String kind,String id,long expected,String bodyHash){
        if(command==null||!command.operation().equals(operation)||!command.kind().equals(kind)||!command.id().equals(id)||command.expectedRevision()!=expected||!Objects.equals(command.bodyHash(),bodyHash))throw new Conflict("command_identity_conflict");
    }
    @Override public Receipt committed(String key){if(command==null)throw new Conflict("read_only_provider");return audit.committed(key,commandHash);}
    @Override public Current lock(String kind,String id){
        JdbcFrozenInputs.requireTx();if(command==null||!command.kind().equals(kind)||!command.id().equals(id))throw new Conflict("target_mismatch");
        var row=contents.reviewSnapshot(kind,id).orElse(null);if(row==null)return null;var first=snapshot(row);
        var targets=new ArrayList<>(first.frozen().completeTargets());targets.add(version(row));targets.sort((a,b)->Arrays.compareUnsigned(CanonicalJson.bytes(a),CanonicalJson.bytes(b)));for(var target:targets)validity.lockStableIdentity(target);
        var locked=writer.reviewSnapshot(kind,id);if(locked==null||locked.revision()!=row.revision())throw new Conflict("revision_conflict");return snapshot(locked);
    }
    @Override public Current snapshot(ReviewContentState row){
        if(row==null)return null;var target=ContentVersionKey.fromJson(version(row));var proven=versions.find(target).orElseThrow(()->new Conflict("invalid_evidence"));
        if(!proven.bodyJson().equals(JdbcFrozenInputs.canonical(CanonicalJson.parse(row.bodyJson()))))throw new Conflict("body_hash_mismatch");
        var targets=closure.expand(target,proven.context()).targets();var dependencies=new ArrayList<Dependency>();for(var ref:targets){var state=requiredValidity(ref);dependencies.add(new Dependency(ref,state.revision(),state.effectiveValid()));}
        var own=requiredValidity(version(row));return new Current(row,new Frozen(row.inputJobId(),HexFormat.of().parseHex(proven.inputHash()),targets,true),dependencies,own.effectiveValid());
    }
    @Override public void approve(ReviewContentState row,Stamp stamp,String key){
        requireOperation("approve");var current=snapshot(row);var expected=ReviewPublicationService.stamp(current);if(!expected.equals(stamp))throw new Conflict("review_required");String stampId=JdbcFrozenInputs.stableId("review-stamp",key);
        writer.approve(row,stamp,stampId,current.frozen().inputHash());append(key,new Receipt(row.kind(),row.id(),row.revision()+1,"approved",stamp.hash()));
    }
    @Override public void publish(ReviewContentState row,String key){requireOperation("publish");writer.publish(row);append(key,new Receipt(row.kind(),row.id(),row.revision()+1,"published",HexFormat.of().formatHex(row.reviewStampHash())));}
    @Override public void edit(ReviewContentState row,String body,byte[] bodyHash,String key){requireOperation("edit");audit.archiveCurrent(ContentVersionKey.fromJson(version(row)));writer.edit(row,body,bodyHash);append(key,new Receipt(row.kind(),row.id(),row.revision()+1,"draft",null));}
    private void append(String key,Receipt receipt){var row=contents.reviewSnapshot(receipt.kind(),receipt.id()).orElseThrow(()->new Conflict("content_missing"));audit.append(key,commandHash,command.reason(),receipt,ContentVersionKey.fromJson(version(row)));}
    private VersionValidityService.Validity requiredValidity(JsonNode target){if(!validity.exactVersionExists(target))throw new Conflict("invalid_evidence");var value=validity.validity(target);if(value==null||value.revision()<0||value.revision()>CanonicalJson.MAX_SAFE_INTEGER||value.activeDispositionIds()==null||value.effectiveValid()!=value.activeDispositionIds().isEmpty())throw new Conflict("invalid_evidence");return value;}
    private void requireOperation(String operation){JdbcFrozenInputs.requireTx();if(command==null||!operation.equals(command.operation()))throw new Conflict("command_identity_conflict");}
    private static JsonNode version(ReviewContentState row){if(row.bodyRevision()==null||row.bodyHashV1()==null||row.inputJobId()==null)throw new Conflict("invalid_evidence");var node=JsonMapper.shared().createObjectNode();node.put("kind","content_version");node.put("content_kind",row.kind());node.put("content_id",row.id());node.put("revision",row.bodyRevision());node.put("body_hash",HexFormat.of().formatHex(row.bodyHashV1()));node.put("input_job_id",row.inputJobId());ContentVersionKey.fromJson(node);return node;}
    private static String hash(Command c){var node=JsonMapper.shared().createObjectNode();node.put("operation",c.operation());node.put("kind",c.kind());node.put("id",c.id());node.put("expected_revision",c.expectedRevision());node.put("body_hash",c.bodyHash());node.put("reason",c.reason());return CanonicalJson.hash(CanonicalJson.Domain.ACTION,node);}
}
