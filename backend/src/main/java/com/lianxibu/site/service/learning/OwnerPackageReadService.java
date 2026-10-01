package com.lianxibu.site.service.learning;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Current owner metadata from one consistent package snapshot, including drafts. */
public final class OwnerPackageReadService {
 public record Snapshot(String id,String title,String description,Long revision,String state){}
 @FunctionalInterface public interface Store {
  /** Exact package read. Return null only for a missing package. Unobserved legacy
   * metadata stays null; no public-reader fallback or default-package selection. */
  Snapshot snapshot(String packageId);
 }
 public static final class Missing extends RuntimeException {public Missing(){super("package not found");}}
 public static final class Denied extends RuntimeException {public Denied(){super("owner required");}}
 public static final class Unavailable extends IllegalStateException {public Unavailable(){super("current package unavailable");}}
 private static final Set<String> FIELDS=Set.of("id","title","description","revision","state");
 private static final Set<String> STATES=Set.of("draft","approved","published","withdrawn");
 private static final JsonMapper JSON=JsonMapper.builder().build();
 private final Store store;
 public OwnerPackageReadService(Store store){this.store=Objects.requireNonNull(store,"package store required");}
 public Map<String,Object> read(Actor actor,String packageId){
  if(actor==null||actor.userId()==null||actor.userId().isBlank()||!"owner".equals(actor.role()))throw new Denied();
  WireValues.id(packageId,false);
  Snapshot row;try{row=store.snapshot(packageId);}catch(RuntimeException failure){throw new Unavailable();}
  if(row==null)throw new Missing();
  var data=new LinkedHashMap<String,Object>();data.put("id",row.id());data.put("title",row.title());data.put("description",row.description());data.put("revision",row.revision());data.put("state",row.state());validateProjection(data,packageId);return Collections.unmodifiableMap(data);
 }
 public static void validateProjection(Map<String,Object> data,String packageId){
  try{JsonNode x=CanonicalJson.parse(JSON.writeValueAsString(data));WireValues.object(x,FIELDS,FIELDS);if(!packageId.equals(WireValues.id(x.get("id"),false)))throw new Unavailable();text(x.get("title"),1,200);text(x.get("description"),0,16384);if(!x.get("revision").isNull())WireValues.nonnegativeInteger(x.get("revision"));if(!x.get("state").isNull()&&!STATES.contains(WireValues.text(x.get("state"))))throw new Unavailable();}
  catch(RuntimeException invalid){throw new Unavailable();}
 }
 private static void text(JsonNode x,int min,int max){if(x.isNull())return;String s=WireValues.text(x);int size=s.codePointCount(0,s.length());if(size<min||size>max)throw new Unavailable();}
}
