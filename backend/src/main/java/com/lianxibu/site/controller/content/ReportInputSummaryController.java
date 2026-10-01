package com.lianxibu.site.controller.content;

import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.service.content.ReportInputSummaryReadService;
import com.lianxibu.site.service.identity.IdentityService;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

/** Closed owner projection; measured serialized envelope bytes are the exact bytes sent. */
@RestController
public final class ReportInputSummaryController {
 private static final JsonMapper JSON=JsonMapper.builder().build();
 private static final Set<String> SUMMARY=Set.of("report_id","input_snapshot_id","state","input_hash","generation","input_cutoff_at","report_type","period_start","period_end","timezone","selection_version","coverage","group_counts","total","items","next_cursor");
 private final ObjectProvider<IdentityService> identity;private final ObjectProvider<ReportInputSummaryReadService> reads;
 public ReportInputSummaryController(ObjectProvider<IdentityService> identity,ObjectProvider<ReportInputSummaryReadService> reads){this.identity=identity;this.reads=reads;}
 @GetMapping(path="/admin/reports/{id}/input-summary",produces="application/json")
 public ResponseEntity<byte[]> read(@CookieValue(name="lianxibu_session",required=false)String cookie,@PathVariable("id")String id,@RequestParam MultiValueMap<String,String> query){
  var actor=owner(cookie);for(String field:query.keySet())if(!Set.of("input_snapshot_id","cursor","limit").contains(field)||query.get(field).size()!=1)throw new Failure(400,"protocol_error");WireValues.id(id,false);String snapshot=WireValues.id(query.getFirst("input_snapshot_id"),false);
  int limit=20;String raw=query.getFirst("limit");if(raw!=null){try{if(!raw.matches("[1-9][0-9]*"))throw new IllegalArgumentException();limit=Integer.parseInt(raw);}catch(IllegalArgumentException invalid){throw new Failure(422,"validation");}if(limit>50)throw new Failure(422,"validation");}
  String cursor=query.getFirst("cursor");if(cursor!=null&&(cursor.isEmpty()||cursor.length()>512))throw new Failure(422,"validation");var service=reads.getIfAvailable();if(service==null)throw new Failure(503,"temporarily_unavailable");Map<String,Object> page=service.read(actor,id,snapshot,cursor,limit);
  if(page==null||!id.equals(page.get("report_id"))||!snapshot.equals(page.get("input_snapshot_id")))throw new Failure(503,"temporarily_unavailable");String state=Objects.toString(page.get("state"),"");Set<String> fields=state.equals("summary")?SUMMARY:Set.of("report_id","input_snapshot_id","state","reason");if(!Set.of("summary","unavailable").contains(state)||!page.keySet().equals(fields))throw new Failure(503,"temporarily_unavailable");
  if(state.equals("summary")&&(!(page.get("items") instanceof List<?> items)||items.size()>limit))throw new Failure(503,"temporarily_unavailable");
  byte[] bytes=JSON.writeValueAsBytes(new ContentDtos.Envelope<>(page,meta()));if(bytes.length>1024*1024)throw new Failure(503,"temporarily_unavailable");return ResponseEntity.ok().header("Cache-Control","no-store").header("Content-Type","application/json").body(bytes);
 }
 private IdentityService.Actor owner(String cookie){if(cookie==null||cookie.isBlank())throw new Failure(401,"unauthenticated");var auth=identity.getIfAvailable();if(auth==null)throw new Failure(503,"temporarily_unavailable");var actor=auth.authenticate(cookie);if(actor==null||actor.userId()==null||actor.userId().isBlank())throw new Failure(401,"unauthenticated");if(!"owner".equals(actor.role()))throw new Failure(403,"forbidden");return actor;}
 @ExceptionHandler(Failure.class) ResponseEntity<ContentDtos.ErrorEnvelope> failed(Failure value){return error(value.status,value.code);}
 @ExceptionHandler({DataAccessException.class,IllegalStateException.class}) ResponseEntity<ContentDtos.ErrorEnvelope> unavailable(Exception ignored){return error(503,"temporarily_unavailable");}
 @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<ContentDtos.ErrorEnvelope> invalid(Exception ignored){return error(422,"validation");}
 @ExceptionHandler(ReportInputSummaryReadService.Missing.class) ResponseEntity<ContentDtos.ErrorEnvelope> missing(Exception ignored){return error(404,"not_found");}
 @ExceptionHandler(ReportInputSummaryReadService.Denied.class) ResponseEntity<ContentDtos.ErrorEnvelope> denied(Exception ignored){return error(403,"forbidden");}
 @ExceptionHandler(ReportInputSummaryReadService.SnapshotConflict.class) ResponseEntity<ContentDtos.ErrorEnvelope> snapshotConflict(Exception ignored){return error(409,"snapshot_scope_conflict");}
 @ExceptionHandler(ReportInputSummaryReadService.CursorConflict.class) ResponseEntity<ContentDtos.ErrorEnvelope> cursorConflict(Exception ignored){return error(409,"cursor_scope_conflict");}
 @ExceptionHandler(IdentityService.Rejected.class) ResponseEntity<ContentDtos.ErrorEnvelope> authRejected(IdentityService.Rejected failure){int status=switch(failure.refusal()){case UNAUTHENTICATED->401;case FORBIDDEN->403;case NOT_FOUND->404;case REVISION_CONFLICT,IDENTITY_CONFLICT->409;case RATE_LIMITED->429;case TEMPORARILY_UNAVAILABLE->503;};return error(status,failure.refusal().name().toLowerCase(Locale.ROOT));}
 private static ResponseEntity<ContentDtos.ErrorEnvelope> error(int status,String code){var response=ResponseEntity.status(status).header("Cache-Control","no-store");if(status==429||status==503)response.header("Retry-After","1");return response.body(new ContentDtos.ErrorEnvelope(new ContentDtos.ErrorDetail(code,code),meta()));}
 private static ContentDtos.Meta meta(){return new ContentDtos.Meta(UUID.randomUUID().toString());}
 private static final class Failure extends RuntimeException {final int status;final String code;Failure(int status,String code){this.status=status;this.code=code;}}
}
