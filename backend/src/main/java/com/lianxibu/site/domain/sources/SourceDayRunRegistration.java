package com.lianxibu.site.domain.sources;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Original full-directory identity bound to one daily run. Values alone grant no claim permission. */
public final class SourceDayRunRegistration {
    private static final ZoneId SHANGHAI=ZoneId.of("Asia/Shanghai");
    public record Request(LocalDate day,String rosterHash,Instant registeredAt,SourceDayRoster.RawSource source,String runId){
        public Request {
            checkHash(rosterHash);window(day,registeredAt);Objects.requireNonNull(source);
            if(runId==null||!runId.matches("[A-Za-z0-9._:-]{1,64}")||source.category()==null||!"production".equals(source.activationStage())||!source.effectivePresent()
                    ||source.configValidUntil()==null||!source.configValidUntil().isAfter(registeredAt))throw invalid();
        }
        public String metadataJson(){
            var json=JsonMapper.shared();var root=json.createObjectNode().put("business_date",day.toString()).put("registered_at",stamp(registeredAt))
                    .put("roster_hash",rosterHash).put("run_id",runId).put("schema_version","source-day-run-registration-v1");
            var metadata=json.readTree(new SourceDayRoster.Evidence(registeredAt,1,List.of(source)).metadataJson());root.set("source",metadata.get("sources").get(0));
            return json.writeValueAsString(root);
        }
        public String hash(){
            try{var sha=MessageDigest.getInstance("SHA-256");sha.update("LX:SOURCE_DAY_RUN:1\n".getBytes(StandardCharsets.UTF_8));return HexFormat.of().formatHex(sha.digest(metadataJson().getBytes(StandardCharsets.UTF_8)));}
            catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        }
        @Override public String toString(){return "SourceDayRunRequest[redacted]";}
    }
    public record Reservation(Request request,long generation,Instant reservedAt,String requestHash){
        public Reservation {
            Objects.requireNonNull(request);window(request.day(),reservedAt);checkHash(requestHash);
            if(generation!=1||reservedAt.isBefore(request.registeredAt())||!request.hash().equals(requestHash))throw invalid();
        }
        @Override public String toString(){return "SourceDayRunReservation[redacted]";}
    }
    /** Null is an unverified or unavailable registration; it is never a successful empty run. */
    public interface Api {
        Reservation reserve(Request request);
        Reservation lookup(Request request);
    }
    private static void checkHash(String value){if(value==null||!value.matches("[0-9a-f]{64}"))throw invalid();}
    private static void window(LocalDate day,Instant value){
        if(day==null||day.getYear()<1970||day.getYear()>9999||value==null||value.getNano()%1000!=0
                ||value.isBefore(day.plusDays(1).atTime(0,10).atZone(SHANGHAI).toInstant())||!value.isBefore(day.plusDays(1).atTime(4,30).atZone(SHANGHAI).toInstant()))throw invalid();
    }
    private static String stamp(Instant value){return new DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(value);}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("invalid original source day run registration");}
}
