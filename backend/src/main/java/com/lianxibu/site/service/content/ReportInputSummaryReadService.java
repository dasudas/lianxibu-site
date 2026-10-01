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

/** Owner-only read of complete, hash-verified frozen report inputs. No writes or current-row fallbacks. */
public final class ReportInputSummaryReadService {
 public enum LegacyReason { NOT_READY,UNPROVEN_SNAPSHOT,UNPROVEN_REPORT_LINK }
 public record Snapshot(String ownerId,String inputHash,String snapshotJson) { }
 public record Proof(String reportId,String currentInputSnapshotId,Snapshot snapshot,LegacyReason legacyReason) { }
 public interface Store {
  /** ONE consistent read must prove current report association and exact immutable snapshot owner.
   * Null means unknown report or requested snapshot. Known changed association returns its current ID.
   * Legacy reasons require affirmative persisted absence/state evidence; IO failure is an exception.
   * A missing runtime Store must never substitute a legacy reason. */
  Proof snapshot(String reportId,String requestedSnapshotId);
 }
 public static final class Denied extends RuntimeException { }
 public static final class Missing extends RuntimeException { }
 public static final class SnapshotConflict extends RuntimeException { }
 public static final class CursorConflict extends RuntimeException { }
 private static final byte[] AAD="report-input-summary-v1".getBytes(StandardCharsets.UTF_8);
 private final Store store;private final SecretKeySpec key;private final SecureRandom random=new SecureRandom();
 public ReportInputSummaryReadService(Store store,byte[] stableCursorKey){if(store==null||stableCursorKey==null||stableCursorKey.length!=32)throw new IllegalArgumentException("report input dependencies required");this.store=store;this.key=new SecretKeySpec(stableCursorKey.clone(),"AES");}
 public Map<String,Object> read(Actor actor,String reportId,String snapshotId,String cursor,int limit){
  if(actor==null||actor.userId()==null||actor.userId().isBlank()||!"owner".equals(actor.role()))throw new Denied();WireValues.id(reportId,false);WireValues.id(snapshotId,false);if(limit<1||limit>50||cursor!=null&&(cursor.isEmpty()||cursor.length()>512))throw new IllegalArgumentException("invalid report input query");
  Proof proof=store.snapshot(reportId,snapshotId);if(proof==null)throw new Missing();if(!reportId.equals(proof.reportId()))throw unavailable();if(proof.currentInputSnapshotId()!=null&&!snapshotId.equals(proof.currentInputSnapshotId()))throw new SnapshotConflict();
  if(proof.legacyReason()!=null){if(proof.snapshot()!=null||proof.legacyReason()!=LegacyReason.UNPROVEN_REPORT_LINK&&proof.currentInputSnapshotId()==null||proof.legacyReason()==LegacyReason.UNPROVEN_REPORT_LINK&&proof.currentInputSnapshotId()!=null)throw unavailable();return unavailablePage(reportId,snapshotId,proof.legacyReason().name().toLowerCase(Locale.ROOT),cursor);}
  Snapshot snapshot=proof.snapshot();if(snapshot==null||!snapshotId.equals(proof.currentInputSnapshotId())||!snapshotId.equals(snapshot.ownerId())||snapshot.snapshotJson()==null||snapshot.inputHash()==null||!snapshot.inputHash().matches("[0-9a-f]{64}"))throw unavailable();
  tools.jackson.databind.JsonNode root;Map<String,Object> validated;try{root=CanonicalJson.parse(snapshot.snapshotJson());if(!CanonicalJson.hash(CanonicalJson.Domain.INPUT,root).equals(snapshot.inputHash()))throw unavailable();ReportInputProfile.validateRoot(root);}catch(RuntimeException invalid){throw unavailable();}
  if(!root.has(ReportInputProfile.FIELD))return unavailablePage(reportId,snapshotId,"unproven_group_manifest",cursor);
  try{validated=ReportInputProfile.validated(root);}catch(RuntimeException invalid){throw unavailable();}
  var base=new LinkedHashMap<String,Object>();base.put("report_id",reportId);base.put("input_snapshot_id",snapshotId);base.put("state","summary");base.put("input_hash",snapshot.inputHash());base.putAll(validated);
  @SuppressWarnings("unchecked") var members=(List<Object>)validated.get("items");String scope=hash(reportId)+"\n"+hash(snapshotId)+"\n"+snapshot.inputHash()+"\n"+hash(new String(CanonicalJson.bytes(JsonMapper.builder().build().valueToTree(base)),StandardCharsets.UTF_8));int offset=0;
  if(cursor!=null){String decoded=decode(cursor);String[] fields=decoded.split("\n",-1);if(fields.length!=6||!fields[4].matches("[1-9][0-9]*")||!fields[5].matches("[1-9][0-9]*"))throw new IllegalArgumentException("invalid report cursor");for(int i=0;i<4;i++)if(!fields[i].matches("[0-9a-f]{64}"))throw new IllegalArgumentException("invalid report cursor");if(!String.join("\n",Arrays.copyOf(fields,4)).equals(scope)||!fields[5].equals(Integer.toString(limit)))throw new CursorConflict();try{offset=Integer.parseInt(fields[4]);}catch(NumberFormatException invalid){throw new IllegalArgumentException("invalid report cursor");}if(offset>=members.size()||offset%limit!=0)throw new IllegalArgumentException("invalid report cursor");}
  int end=(int)Math.min((long)offset+limit,members.size());base.put("items",List.copyOf(members.subList(offset,end)));base.put("next_cursor",end<members.size()?encode(scope+"\n"+end+"\n"+limit):null);return Collections.unmodifiableMap(base);
 }
 private static Map<String,Object> unavailablePage(String report,String snapshot,String reason,String cursor){if(cursor!=null)throw new IllegalArgumentException("unavailable input has no cursor");return Map.of("report_id",report,"input_snapshot_id",snapshot,"state","unavailable","reason",reason);}
 private String encode(String value){try{byte[] nonce=new byte[12];random.nextBytes(nonce);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,nonce));cipher.updateAAD(AAD);byte[] encrypted=cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));byte[] result=Arrays.copyOf(nonce,nonce.length+encrypted.length);System.arraycopy(encrypted,0,result,nonce.length,encrypted.length);String encoded=Base64.getUrlEncoder().withoutPadding().encodeToString(result);if(encoded.length()>512)throw unavailable();return encoded;}catch(GeneralSecurityException failure){throw unavailable();}}
 private String decode(String value){try{if(!value.matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException("invalid report cursor");byte[] data=Base64.getUrlDecoder().decode(value);if(data.length<=28||!Base64.getUrlEncoder().withoutPadding().encodeToString(data).equals(value))throw new IllegalArgumentException("invalid report cursor");Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Arrays.copyOf(data,12)));cipher.updateAAD(AAD);return new String(cipher.doFinal(data,12,data.length-12),StandardCharsets.UTF_8);}catch(GeneralSecurityException failure){throw new IllegalArgumentException("invalid report cursor");}}
 private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException failure){throw unavailable();}}
 private static IllegalStateException unavailable(){return new IllegalStateException("frozen report input unavailable");}
}
