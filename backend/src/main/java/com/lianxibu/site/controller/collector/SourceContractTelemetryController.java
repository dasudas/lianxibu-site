package com.lianxibu.site.controller.collector;
import com.lianxibu.site.domain.protocol.CanonicalJson;import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.service.identity.NodeIdentityService;import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.sources.SourceContractSnapshotService;
import jakarta.servlet.http.HttpServletRequest;import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;import org.springframework.http.*;import org.springframework.web.bind.annotation.*;
/** Authenticated closed health route, independent of the frozen observation and heartbeat requests. */
@RestController @RequestMapping("/api/collector/v1/source-contract-telemetry")
@ConditionalOnProperty(name={"site.ops.source-contract.enabled","site.browser.enabled","site.collector.enabled","site.sources.enabled"},havingValue="true")
public final class SourceContractTelemetryController {
 private final NodeIdentityService nodes;private final SourceContractSnapshotService snapshots;
 public SourceContractTelemetryController(NodeIdentityService nodes,SourceContractSnapshotService snapshots){this.nodes=Objects.requireNonNull(nodes);this.snapshots=Objects.requireNonNull(snapshots);}
 @PutMapping(path="/{source_id}",consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
 public ResponseEntity<?> put(HttpServletRequest request,@PathVariable("source_id")String source,@RequestBody(required=false)byte[] raw){
  try{
   var headers=Collections.list(request.getHeaders("Authorization"));if(headers.size()>1)return failure(400,"protocol_error");String header=headers.isEmpty()?null:headers.getFirst();if(header==null||!header.matches("Bearer [A-Za-z0-9_-]{43}"))return failure(401,"unauthenticated");
   WireValues.id(source,true);String bearer=header.substring(7);nodes.authorizeBearer(bearer,"heartbeat:write",source);
   if(request.getQueryString()!=null||request.getHeader("Idempotency-Key")!=null)return failure(400,"protocol_error");
   if(raw==null||raw.length==0)return failure(400,"protocol_error");if(raw.length>32768)return failure(413,"payload_too_large");try{CanonicalJson.parse(raw);}catch(IllegalArgumentException invalid){return failure(400,"protocol_error");}
   var receipt=snapshots.submit(bearer,source,raw);return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store").body(Map.of("data",CanonicalJson.parse(receipt.ackJson()),"meta",Map.of("request_id",UUID.randomUUID().toString())));
  }catch(IdentityService.Rejected denied){return failure(403,"forbidden");}
   catch(SourceContractSnapshotService.Rejected denied){return switch(denied.code()){case "stale_config"->failure(409,"stale_config");case "forbidden"->failure(403,"forbidden");default->failure(422,"validation");};}
   catch(WireValues.UnknownMember invalid){return failure(400,"protocol_error");}
   catch(IllegalArgumentException invalid){return failure(422,"validation");}
   catch(RuntimeException unavailable){return failure(503,"temporarily_unavailable");}
 }
 private static ResponseEntity<?> failure(int status,String code){var builder=ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL,"no-store");if(status==503)builder.header(HttpHeaders.RETRY_AFTER,"1");return builder.body(Map.of("error",Map.of("code",code,"message",code),"meta",Map.of("request_id",UUID.randomUUID().toString())));}
}
