package com.lianxibu.site.domain.sources;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SourceDayRunRegistrationTest {
    final LocalDate day=LocalDate.of(2026,9,20);final Instant registered=Instant.parse("2026-09-20T16:10:00.000001Z");
    final String hash="a".repeat(64);
    SourceDayRoster.RawSource source(String category,String stage,boolean effective,Instant valid,long revision){return new SourceDayRoster.RawSource("fictional-🟣",category,revision,3,stage,"disabled",valid,false,effective,effective?hash:null);}
    SourceDayRunRegistration.Request request(){return new SourceDayRunRegistration.Request(day,hash,registered,source("agent","production",true,registered.plusSeconds(10),2),"fictional-run");}
    @Test void originalRequestRetainsExactRawFieldsAndNamespaceHash() throws Exception {
        var value=request();assertEquals("disabled",value.source().legacyState());assertEquals(hash,value.source().effectiveConfigHash());assertEquals(value.metadataJson(),request().metadataJson());
        var sha=MessageDigest.getInstance("SHA-256");sha.update("LX:SOURCE_DAY_RUN:1\n".getBytes(StandardCharsets.UTF_8));assertEquals(HexFormat.of().formatHex(sha.digest(value.metadataJson().getBytes(StandardCharsets.UTF_8))),value.hash());
        assertFalse(value.metadataJson().contains("effective_config_json"));assertFalse(value.toString().contains("🟣"));
    }
    @Test void changedDirectorySourceRevisionAndRunIdentityChangeOriginalHash(){
        var original=request();assertNotEquals(original.hash(),new SourceDayRunRegistration.Request(day,"b".repeat(64),registered,original.source(),original.runId()).hash());
        assertNotEquals(original.hash(),new SourceDayRunRegistration.Request(day,hash,registered,source("agent","production",true,registered.plusSeconds(10),4),original.runId()).hash());
        assertNotEquals(original.hash(),new SourceDayRunRegistration.Request(day,hash,registered,original.source(),"FICTIONAL-run").hash());
    }
    @Test void unassignedInactiveMissingOrExpiredConfigurationCannotRegister(){
        for(var raw:List.of(source(null,"production",true,registered.plusSeconds(10),2),source("agent","disabled",true,registered.plusSeconds(10),2),source("agent","production",false,registered.plusSeconds(10),2),source("agent","production",true,registered,2),source("agent","production",true,null,2)))assertThrows(IllegalArgumentException.class,()->new SourceDayRunRegistration.Request(day,hash,registered,raw,"fictional-run"));
    }
    @Test void originalWindowAndStrictMicrosecondsAndAsciiIdentityAreRequired(){
        var source=request().source();for(var time:List.of(registered.minusSeconds(1),Instant.parse("2026-09-20T20:30:00.000000Z"),registered.plusNanos(1)))assertThrows(IllegalArgumentException.class,()->new SourceDayRunRegistration.Request(day,hash,time,source,"fictional-run"));
        assertThrows(IllegalArgumentException.class,()->new SourceDayRunRegistration.Request(day,hash,registered,source,"🟣"));assertThrows(IllegalArgumentException.class,()->new SourceDayRunRegistration.Request(day,"A".repeat(64),registered,source,"fictional-run"));
    }
    @Test void reservationKeepsOriginalGenerationReceiptAndTimeBounds(){
        var original=request();var value=new SourceDayRunRegistration.Reservation(original,1,registered.plusNanos(1000),original.hash());assertEquals(original,value.request());
        assertThrows(IllegalArgumentException.class,()->new SourceDayRunRegistration.Reservation(original,2,registered,original.hash()));assertThrows(IllegalArgumentException.class,()->new SourceDayRunRegistration.Reservation(original,1,registered.minusNanos(1000),original.hash()));
        assertThrows(IllegalArgumentException.class,()->new SourceDayRunRegistration.Reservation(original,1,Instant.parse("2026-09-20T20:30:00.000000Z"),original.hash()));assertThrows(IllegalArgumentException.class,()->new SourceDayRunRegistration.Reservation(original,1,registered,"b".repeat(64)));
    }
}
