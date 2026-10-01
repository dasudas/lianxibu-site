package com.lianxibu.site.service.learning;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Package lifecycle owns package visibility; object answers and private records are independent. */
public final class PackageLifecycleService {
 public enum Action {APPROVE,REJECT,PUBLISH,WITHDRAW}
 public record Current(String packageId,Long revision,String state){}
 public record Validation(String packageId,long revision,boolean valid){}
 public record Result(String packageId,long revision,String state,boolean replayed){}
 public interface Store {
  /** Durable exact key+command receipt. A different command for this key must reject. */
  Result committed(String actionKey,String commandHash);
  /** Lock the exact current package after the shared entry lock; null means absent. */
  Current locked(String packageId);
  /** In the SAME transaction and locked snapshot, validate the complete current
   * package object/relation structure. Null means unproven. The proof must identify
   * this package and current revision; old import success/state alone is not proof.
   * No network work or permanent schema invention belongs in this port. */
  Validation validateLocked(Current row);
  /** CAS package state+revision and durable receipt together; do not modify object
   * bodies/answers, user records or preferences. Any failure must roll back both. */
  void transition(Current row,String target,String reason,String commandHash,ActionContext action);
 }
 public static final class Rejected extends RuntimeException {public Rejected(String code){super(code);}}
 public static final class Denied extends RuntimeException {public Denied(){super("owner required");}}
 public static final class Unavailable extends IllegalStateException {public Unavailable(){super("package state or validation proof unavailable");}}
 private static final Set<String> STATES=Set.of("draft","approved","published","withdrawn");
 private static final JsonMapper JSON=JsonMapper.builder().build();
 private final SharedWriteExecutor writes;private final Store store;
 public PackageLifecycleService(SharedWriteExecutor writes,Store store){this.writes=Objects.requireNonNull(writes);this.store=Objects.requireNonNull(store);}
 public Result action(Actor actor,String packageId,long expectedRevision,Action requested,String reason,ActionContext action){
  if(actor==null||actor.userId()==null||actor.userId().isBlank()||!"owner".equals(actor.role()))throw new Denied();
  WireValues.id(actor.userId(),false);WireValues.id(packageId,false);
  if(expectedRevision<0||expectedRevision>=CanonicalJson.MAX_SAFE_INTEGER||requested==null||reason==null||reason.isBlank()||reason.codePointCount(0,reason.length())>2048||action==null)throw new IllegalArgumentException("invalid package command");
  WireValues.id(action.preallocatedResourceId(),false);String key=action.actionKey();if(key.isEmpty()||key.length()>128||key.chars().anyMatch(c->c<33||c>126))throw new IllegalArgumentException("invalid package command key");
  var command=JSON.createObjectNode();command.put("actor_id",actor.userId());command.put("package_id",packageId);command.put("expected_revision",expectedRevision);command.put("action",requested.name());command.put("reason",reason);
  String hash=digest(CanonicalJson.bytes(command));String target=switch(requested){case APPROVE->"approved";case REJECT->"draft";case PUBLISH->"published";case WITHDRAW->"withdrawn";};
  return writes.execute(action,k->receipt(k,hash,packageId,expectedRevision,target,true),context->{
   Result existing=receipt(context.actionKey(),hash,packageId,expectedRevision,target,true);if(existing!=null)return existing;
   Current row=store.locked(packageId);if(row==null)throw new Rejected("not_found");if(!packageId.equals(row.packageId())||row.revision()==null||row.revision()<0||row.revision()>CanonicalJson.MAX_SAFE_INTEGER||row.state()==null||!STATES.contains(row.state()))throw new Unavailable();if(row.revision()!=expectedRevision)throw new Rejected("revision_conflict");
   boolean allowed=switch(requested){case APPROVE->Set.of("draft","approved","withdrawn").contains(row.state());case REJECT->Set.of("draft","approved").contains(row.state());case PUBLISH->row.state().equals("approved");case WITHDRAW->Set.of("approved","published").contains(row.state());};if(!allowed)throw new Rejected("invalid_package_state");
   if(requested==Action.APPROVE||requested==Action.PUBLISH){Validation proof=store.validateLocked(row);if(proof==null||!packageId.equals(proof.packageId())||proof.revision()!=expectedRevision)throw new Unavailable();if(!proof.valid())throw new Rejected("package_validation_failed");}
   store.transition(row,target,reason,hash,context);Result saved=receipt(context.actionKey(),hash,packageId,expectedRevision,target,false);if(saved==null)throw new Unavailable();return saved;
  });
 }
 private Result receipt(String key,String hash,String packageId,long expected,String target,boolean replay){Result old=store.committed(key,hash);if(old==null)return null;if(!packageId.equals(old.packageId())||old.revision()!=expected+1||!target.equals(old.state()))throw new Unavailable();return new Result(old.packageId(),old.revision(),old.state(),replay);}
 private static String digest(byte[] bytes){try{var hash=MessageDigest.getInstance("SHA-256");hash.update("learning-package-command-v1\n".getBytes(StandardCharsets.UTF_8));return HexFormat.of().formatHex(hash.digest(bytes));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 unavailable",impossible);}}
}
