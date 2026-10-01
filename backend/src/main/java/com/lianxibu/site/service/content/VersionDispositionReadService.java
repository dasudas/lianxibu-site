package com.lianxibu.site.service.content;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.ContentVersionKey;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.repository.content.JdbcVersionDispositionReads;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.jobs.VersionValidityService;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Read-only evidence chain; production proof Store is assembled separately with the writer. */
public final class VersionDispositionReadService {
 public record Item(String id,String reason,Instant createdAt) { }
 public record Page(ContentVersionKey target,long validityRevision,boolean effectiveValid,List<Item> items,long page,int pageSize,long total) { }
 private record Event(Item item,long revision,String action,String invalidationId) { }
 public static final class Denied extends RuntimeException { }
 public static final class Missing extends RuntimeException { }
 private static final Set<String> KINDS=Set.of("report","model","agent","skill","workflow"),FIELDS=Set.of("schema_version","action","validity_revision","invalidation_id");
 private final JdbcVersionDispositionReads store;private final VersionValidityService.Store proof;private final TransactionTemplate reads;
 public VersionDispositionReadService(JdbcVersionDispositionReads store,VersionValidityService.Store proof,PlatformTransactionManager tx){if(store==null||proof==null||tx==null)throw new IllegalArgumentException("read dependencies required");this.store=store;this.proof=proof;reads=new TransactionTemplate(tx);reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);}
 public Page list(Actor actor,String kind,String id,long revision,String bodyHash,String inputJobId,long page,int size){
  if(actor==null||actor.userId()==null||actor.userId().isBlank()||!"owner".equals(actor.role()))throw new Denied();
  if(kind==null||!KINDS.contains(kind)||page<1||page>CanonicalJson.MAX_SAFE_INTEGER||size<1||size>50)throw new IllegalArgumentException("invalid version query");
  var node=JsonMapper.builder().build().createObjectNode();node.put("kind","content_version");node.put("content_kind",kind);node.put("content_id",id);node.put("revision",revision);node.put("body_hash",bodyHash);if(inputJobId==null)node.putNull("input_job_id");else node.put("input_job_id",inputJobId);
  var target=ContentVersionKey.fromJson(node);byte[] bytes=CanonicalJson.bytes(node),hash=hash(node);
  return reads.execute(status->{
   VersionValidityService.Validity expected;
   try{if(!proof.exactVersionExists(node))throw new Missing();expected=proof.validity(node);}catch(IllegalArgumentException error){throw unavailable();}
   if(expected==null||expected.activeDispositionIds()==null||expected.revision()<0||expected.revision()>CanonicalJson.MAX_SAFE_INTEGER)throw unavailable();
   var events=new TreeMap<Long,Event>();var ids=new java.util.HashSet<String>();Instant after=null;String afterId="";
   while(true){var batch=store.scan(hash,after,afterId,500);if(batch.isEmpty())break;
    for(var row:batch){var event=parse(row,bytes,hash);if(!ids.add(event.item().id())||events.putIfAbsent(event.revision(),event)!=null)throw unavailable();}
    var last=batch.getLast();after=last.createdAt();afterId=last.id();if(batch.size()<500)break;
   }
   var active=new LinkedHashMap<String,Item>();long current=0;
   for(var event:events.values()){if(event.revision()!=++current)throw unavailable();if(event.action().equals("invalidate_conclusion"))active.put(event.item().id(),event.item());else if(active.remove(event.invalidationId())==null)throw unavailable();}
   if(current!=expected.revision()||!active.keySet().equals(expected.activeDispositionIds())||expected.effectiveValid()!=active.isEmpty())throw unavailable();
   var ordered=new ArrayList<>(active.values());ordered.sort(Comparator.comparing(Item::createdAt).thenComparing(Item::id));long offset=(page-1)*size;List<Item> items=offset>=ordered.size()?List.of():List.copyOf(ordered.subList((int)offset,(int)Math.min(offset+size,ordered.size())));
   return new Page(target,current,active.isEmpty(),items,page,size,ordered.size());
  });
 }
 private static Event parse(JdbcVersionDispositionReads.Row row,byte[] targetBytes,byte[] targetHash){
  try{WireValues.id(row.id(),false);if(row.targetJson()==null||row.payloadJson()==null||row.createdAt()==null||row.reason()==null||row.reason().isBlank()||row.reason().codePointCount(0,row.reason().length())>2048)throw unavailable();
   var target=CanonicalJson.parse(row.targetJson());ContentVersionKey.fromJson(target);if(!Arrays.equals(targetBytes,CanonicalJson.bytes(target))||!Arrays.equals(targetHash,row.targetHash()))throw unavailable();
   var payload=CanonicalJson.parse(row.payloadJson());WireValues.object(payload,FIELDS,FIELDS);if(!Arrays.equals(hash(payload),row.payloadHash())||!"content-validity-v1".equals(WireValues.text(payload.get("schema_version"))))throw unavailable();
   String action=WireValues.text(payload.get("action"));long validity=WireValues.nonnegativeInteger(payload.get("validity_revision"));if(validity<1||!Set.of("invalidate_conclusion","reverse_invalidation").contains(action))throw unavailable();
   var invalidation=payload.get("invalidation_id");String reverse=invalidation.isNull()?null:WireValues.id(invalidation,false);if(action.equals("invalidate_conclusion")?reverse!=null:reverse==null)throw unavailable();
   return new Event(new Item(row.id(),row.reason(),row.createdAt()),validity,action,reverse);
  }catch(IllegalArgumentException error){throw unavailable();}
 }
 private static byte[] hash(JsonNode value){try{return MessageDigest.getInstance("SHA-256").digest(CanonicalJson.bytes(value));}catch(java.security.NoSuchAlgorithmException error){throw unavailable();}}
 private static IllegalStateException unavailable(){return new IllegalStateException("version proof unavailable");}
}
