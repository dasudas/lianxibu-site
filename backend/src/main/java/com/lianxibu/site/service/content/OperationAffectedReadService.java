package com.lianxibu.site.service.content;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.json.JsonMapper;

/** Safe reads of a permanently committed operation's frozen impact proof. */
public final class OperationAffectedReadService {
 public record Ref(String kind,String id,String packageId) { }
 public record Snapshot(String operationRef,boolean complete,List<Ref> resources) { }
 public record Page(String operationRef,String state,Long total,List<Ref> items,String nextCursor) { }
 public interface Store {
  /** Null means unknown operation. Pending has no published set; complete is immutable.
   * Implementations must atomically verify permanent operation identity and traversal completion.
   * Current graph inference or a partially collected set cannot satisfy this port. */
  Snapshot snapshot(String operationRef);
 }
 public static final class Denied extends RuntimeException { }
 public static final class Missing extends RuntimeException { }
 public static final class CursorConflict extends RuntimeException { }
 private final Store store;private final SecretKeySpec key;private final SecureRandom random=new SecureRandom();
 public OperationAffectedReadService(Store store,byte[] stableCursorKey){if(store==null||stableCursorKey==null||stableCursorKey.length!=32)throw new IllegalArgumentException("impact dependencies required");this.store=store;key=new SecretKeySpec(stableCursorKey.clone(),"AES");}
 public Page read(Actor actor,String operationRef,String cursor,int limit){
  if(actor==null||actor.userId()==null||actor.userId().isBlank()||!"owner".equals(actor.role()))throw new Denied();
  WireValues.id(operationRef,false);if(limit<1||limit>50||cursor!=null&&(cursor.isEmpty()||cursor.length()>512))throw new IllegalArgumentException("invalid impact query");
  Snapshot proof=store.snapshot(operationRef);if(proof==null)throw new Missing();
  if(!operationRef.equals(proof.operationRef())||proof.resources()==null)throw unavailable();
  if(!proof.complete()){if(!proof.resources().isEmpty())throw unavailable();if(cursor!=null)throw new IllegalArgumentException("pending impact has no cursor");return new Page(operationRef,"pending",null,List.of(),null);}
  List<Ref> sorted=validated(proof.resources());String fingerprint=fingerprint(operationRef,sorted),operationHash=hash(operationRef.getBytes(StandardCharsets.UTF_8));int offset=0;
  if(cursor!=null){String[] fields=decode(cursor).split("\n",-1);if(fields.length!=4||!fields[0].matches("[0-9a-f]{64}")||!fields[1].matches("[0-9a-f]{64}")||!fields[2].matches("[1-9][0-9]*")||!fields[3].matches("[1-9][0-9]*"))throw new IllegalArgumentException("invalid impact cursor");if(!fields[0].equals(operationHash)||!fields[1].equals(fingerprint)||!fields[3].equals(Integer.toString(limit)))throw new CursorConflict();try{offset=Integer.parseInt(fields[2]);}catch(NumberFormatException ignored){throw new IllegalArgumentException("invalid impact cursor");}if(offset>=sorted.size()||offset%limit!=0)throw new IllegalArgumentException("invalid impact cursor");}
  int end=(int)Math.min((long)offset+limit,sorted.size());String next=end<sorted.size()?encode(operationHash+"\n"+fingerprint+"\n"+end+"\n"+limit):null;
  return new Page(operationRef,"complete",(long)sorted.size(),List.copyOf(sorted.subList(offset,end)),next);
 }
 private static List<Ref> validated(List<Ref> values){
  var refs=new TreeSet<Ref>(Comparator.comparing(Ref::kind).thenComparing(Ref::id).thenComparing(Ref::packageId,Comparator.nullsFirst(Comparator.naturalOrder())));
  for(Ref value:values){try{if(value==null||value.kind()==null||value.kind().isEmpty()||value.kind().codePointCount(0,value.kind().length())>32||value.kind().codePoints().anyMatch(Character::isISOControl))throw unavailable();WireValues.id(value.kind(),false);WireValues.id(value.id(),false);if(value.packageId()!=null)WireValues.id(value.packageId(),false);}catch(IllegalArgumentException ignored){throw unavailable();}refs.add(value);}return List.copyOf(refs);
 }
 private static String fingerprint(String operation,List<Ref> refs){var mapper=JsonMapper.builder().build();var root=mapper.createObjectNode();root.put("operation_ref",operation);var list=root.putArray("items");for(Ref ref:refs){var item=list.addObject();item.put("kind",ref.kind());item.put("id",ref.id());if(ref.packageId()==null)item.putNull("package_id");else item.put("package_id",ref.packageId());}return hash(CanonicalJson.bytes(root));}
 private String encode(String value){try{byte[] nonce=new byte[12];random.nextBytes(nonce);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,nonce));cipher.updateAAD("operation-affected-v1".getBytes(StandardCharsets.UTF_8));byte[] encrypted=cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)),result=Arrays.copyOf(nonce,nonce.length+encrypted.length);System.arraycopy(encrypted,0,result,nonce.length,encrypted.length);return Base64.getUrlEncoder().withoutPadding().encodeToString(result);}catch(GeneralSecurityException error){throw unavailable();}}
 private String decode(String value){try{if(!value.matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException("invalid impact cursor");byte[] bytes=Base64.getUrlDecoder().decode(value);if(bytes.length<=28||!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(value))throw new IllegalArgumentException("invalid impact cursor");Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Arrays.copyOf(bytes,12)));cipher.updateAAD("operation-affected-v1".getBytes(StandardCharsets.UTF_8));return new String(cipher.doFinal(bytes,12,bytes.length-12),StandardCharsets.UTF_8);}catch(GeneralSecurityException error){throw new IllegalArgumentException("invalid impact cursor");}}
 private static String hash(byte[] value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(NoSuchAlgorithmException error){throw unavailable();}}
 private static IllegalStateException unavailable(){return new IllegalStateException("impact proof unavailable");}
}
