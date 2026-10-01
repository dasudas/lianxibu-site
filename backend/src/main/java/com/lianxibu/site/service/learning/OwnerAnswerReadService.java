package com.lianxibu.site.service.learning;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Owner-only current answer; public readers and answer writers remain independent. */
public final class OwnerAnswerReadService {
 public record Snapshot(String packageId,String questionId,String questionTitle,Long revision,
                        Long answerRevision,String answerState,String generationState,String answer,
                        boolean reviewKnown,byte[] reviewHash,Boolean packagePublished,Boolean questionVisible){
  public Snapshot {reviewHash=reviewHash==null?null:reviewHash.clone();}
  @Override public byte[] reviewHash(){return reviewHash==null?null:reviewHash.clone();}
 }
 @FunctionalInterface public interface Store {
  /** Exact composite identity and ALL answer/review/package/question facts in ONE
   * consistent read. Null means missing. Legacy unknown is explicit null/reviewKnown=false,
   * never inferred from a public answer read or separate current-row queries. */
  Snapshot snapshot(String packageId,String questionId);
 }
 public static final class Missing extends RuntimeException {public Missing(){super("answer not found");}}
 public static final class Denied extends RuntimeException {public Denied(){super("owner required");}}
 public static final class Unavailable extends IllegalStateException {public Unavailable(){super("current answer proof unavailable");}}
 private static final Set<String> FIELDS=Set.of("package_id","question_id","question_title","revision","answer_revision","answer","answer_state","generation_state","review_state","publicly_readable");
 private static final Set<String> STATES=Set.of("draft","approved","published","withdrawn");
 private static final Set<String> GENERATION=Set.of("not_generated","generated","reviewed","needs_revision");
 private static final JsonMapper JSON=JsonMapper.builder().build();
 private final Store store;
 public OwnerAnswerReadService(Store store){this.store=Objects.requireNonNull(store,"answer store required");}
 public Map<String,Object> read(Actor actor,String packageId,String questionId){
  if(actor==null||actor.userId()==null||actor.userId().isBlank()||!"owner".equals(actor.role()))throw new Denied();
  WireValues.id(packageId,false);WireValues.id(questionId,false);
  Snapshot row;try{row=store.snapshot(packageId,questionId);}catch(RuntimeException failure){throw new Unavailable();}
  if(row==null)throw new Missing();
  try{
   if(!packageId.equals(row.packageId())||!questionId.equals(row.questionId())||!row.reviewKnown()&&row.reviewHash()!=null||row.reviewHash()!=null&&row.reviewHash().length!=32)throw new Unavailable();
   String review=!row.reviewKnown()?"unknown":row.reviewHash()==null?"unreviewed":row.answer()!=null&&Arrays.equals(row.reviewHash(),AnswerLifecycleService.answerHash(row.answer()))?"current":"stale";
   if(row.reviewHash()!=null&&row.answer()==null)throw new Unavailable();
   Boolean state=row.answerState()==null?null:row.answerState().equals("published");Boolean reviewed=review.equals("unknown")?null:review.equals("current");
   Boolean visible=conjunction(row.packagePublished(),row.questionVisible(),state,reviewed);
   var data=new LinkedHashMap<String,Object>();data.put("package_id",packageId);data.put("question_id",questionId);data.put("question_title",row.questionTitle());data.put("revision",row.revision());data.put("answer_revision",row.answerRevision());data.put("answer",row.answer());data.put("answer_state",row.answerState());data.put("generation_state",row.generationState());data.put("review_state",review);data.put("publicly_readable",visible);
   validateProjection(data,packageId,questionId);return Collections.unmodifiableMap(data);
  }catch(RuntimeException invalid){throw new Unavailable();}
 }
 /** A closed projection can be checked again at the HTTP boundary without exposing hashes. */
 public static void validateProjection(Map<String,Object> data,String packageId,String questionId){
  try{
   JsonNode x=CanonicalJson.parse(JSON.writeValueAsString(data));WireValues.object(x,FIELDS,FIELDS);
   if(!packageId.equals(WireValues.id(x.get("package_id"),false))||!questionId.equals(WireValues.id(x.get("question_id"),false)))throw new Unavailable();
   text(x.get("question_title"),1,512);text(x.get("answer"),0,50000);integer(x.get("revision"),1);integer(x.get("answer_revision"),0);
   one(x.get("answer_state"),STATES,true);one(x.get("generation_state"),GENERATION,true);one(x.get("review_state"),Set.of("unknown","unreviewed","current","stale"),false);
   JsonNode visible=x.get("publicly_readable");if(!visible.isNull()&&!visible.isBoolean())throw new Unavailable();
   boolean needsAnswer="published".equals(x.get("answer_state").asText())||"current".equals(x.get("review_state").asText());if(needsAnswer&&(x.get("answer").isNull()||x.get("answer").textValue().isEmpty()))throw new Unavailable();
   if(visible.isBoolean()&&visible.booleanValue()&&(!"published".equals(x.get("answer_state").asText())||!"current".equals(x.get("review_state").asText())))throw new Unavailable();
  }catch(RuntimeException invalid){throw new Unavailable();}
 }
 private static Boolean conjunction(Boolean... values){boolean unknown=false;for(Boolean x:values){if(Boolean.FALSE.equals(x))return false;if(x==null)unknown=true;}return unknown?null:true;}
 private static void text(JsonNode x,int min,int max){if(x.isNull())return;String s=WireValues.text(x);int length=s.codePointCount(0,s.length());if(length<min||length>max)throw new Unavailable();}
 private static void integer(JsonNode x,int min){if(!x.isNull()&&WireValues.nonnegativeInteger(x)<min)throw new Unavailable();}
 private static void one(JsonNode x,Set<String> values,boolean nullable){if(x.isNull()&&nullable)return;if(!values.contains(WireValues.text(x)))throw new Unavailable();}
}
