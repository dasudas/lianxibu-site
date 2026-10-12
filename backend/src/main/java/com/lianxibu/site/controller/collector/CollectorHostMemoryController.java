package com.lianxibu.site.controller.collector;
import com.lianxibu.site.domain.protocol.CanonicalJson;import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.service.identity.NodeIdentityService;import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.jobs.CollectorHostMemoryService;import com.lianxibu.site.repository.jobs.JdbcCollectorHostMemory;
import jakarta.servlet.http.HttpServletRequest;import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;import org.springframework.http.*;import org.springframework.web.bind.annotation.*;
/** Independent closed native memory route using the existing current node authority. */
@RestController @RequestMapping("/api/collector/v1/host-memory")
@ConditionalOnProperty(name={"site.ops.collector-memory.enabled","site.browser.enabled","site.collector.enabled"},havingValue="true")
public final class CollectorHostMemoryController {
 private final NodeIdentityService nodes;private final CollectorHostMemoryService snapshots;
 public CollectorHostMemoryController(NodeIdentityService nodes,CollectorHostMemoryService snapshots){this.nodes=Objects.requireNonNull(nodes);this.snapshots=Objects.requireNonNull(snapshots);}
 @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
 public ResponseEntity<?> post(HttpServletRequest request,@RequestBody(required=false)byte[] raw){
  try{
   var headers=Collections.list(request.getHeaders("Authorization"));if(headers.size()>1)return failure(400,"protocol_error");String header=headers.isEmpty()?null:headers.getFirst();if(header==null||!header.matches("Bearer [A-Za-z0-9_-]{43}"))return failure(401,"unauthenticated");
   String bearer=header.substring(7);nodes.authorizeBearer(bearer,"heartbeat:write",null);
   if(request.getQueryString()!=null||request.getHeader("Idempotency-Key")!=null)return failure(400,"protocol_error");
   if(raw==null||raw.length==0)return failure(400,"protocol_error");if(raw.length>8192)return failure(413,"payload_too_large");try{CanonicalJson.parse(raw);}catch(IllegalArgumentException invalid){return failure(400,"protocol_error");}
   var receipt=snapshots.submit(bearer,raw);var sample=receipt.input().sample();return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store").body(Map.of("data",Map.of("capture_epoch",sample.captureEpoch(),"sequence",sample.sequence(),"payload_hash",receipt.input().payloadHash(),"received_at",new java.time.format.DateTimeFormatterBuilder().appendInstant(6).toFormatter(java.util.Locale.ROOT).format(receipt.receivedAt())),"meta",Map.of("request_id",UUID.randomUUID().toString())));
  }catch(IdentityService.Rejected denied){return failure(403,"forbidden");}
   catch(JdbcCollectorHostMemory.Conflict conflict){return failure(409,"snapshot_conflict");}
   catch(WireValues.UnknownMember invalid){return failure(400,"protocol_error");}
   catch(IllegalArgumentException invalid){return failure(422,"validation");}
   catch(RuntimeException unavailable){return failure(503,"temporarily_unavailable");}
 }
 private static ResponseEntity<?> failure(int status,String code){var builder=ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL,"no-store");if(status==503)builder.header(HttpHeaders.RETRY_AFTER,"1");return builder.body(Map.of("error",Map.of("code",code,"message",code),"meta",Map.of("request_id",UUID.randomUUID().toString())));}
}
