package com.lianxibu.site.domain.sources;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SourceDayCollectionAdmissionTest {
    final LocalDate day=LocalDate.of(2026,9,20);final Instant registered=Instant.parse("2026-09-20T16:10:00.000001Z"),sealed=registered.plusNanos(1000);
    final String directoryHash="a".repeat(64),bundleHash="b".repeat(64);
    Map<String,Long> counts(){return new HashMap<>(Map.of("news_tech",0L,"agent",2L,"skill",0L,"workflow",0L,"model",0L));}
    SourceDayRunRegistration.Request request(String id){return new SourceDayRunRegistration.Request(day,directoryHash,registered,new SourceDayRoster.RawSource(id,"agent",2,3,"production","disabled",registered.plusSeconds(86400),false,true,"c".repeat(64)),"fictional-run-"+id);}
    SourceDayCollectionAdmission.SealedDay view(long total,long unassigned,Map<String,Long> counts,List<SourceDayRunRegistration.Request> requests){return new SourceDayCollectionAdmission.SealedDay(day,directoryHash,bundleHash,registered,sealed,total,unassigned,counts,requests);}
    @Test void completeCountsAndOriginalRequestsAreImmutableAndUnassignedIsRetained(){
        var counts=counts();var requests=new ArrayList<>(List.of(request("fictional-a"),request("fictional-b")));var view=view(3,1,counts,requests);counts.put("agent",0L);requests.clear();assertEquals(2L,view.cohortCounts().get("agent"));assertEquals(2,view.requests().size());assertEquals(1,view.unassignedCount());assertThrows(UnsupportedOperationException.class,()->view.requests().clear());assertThrows(UnsupportedOperationException.class,()->view.cohortCounts().clear());assertFalse(view.toString().contains("fictional-a"));
    }
    @Test void missingCategoriesWrongTotalAndDuplicateOrForeignRequestsAreRejected(){
        var missing=counts();missing.remove("model");assertThrows(RuntimeException.class,()->view(3,1,missing,List.of()));assertThrows(RuntimeException.class,()->view(4,1,counts(),List.of()));
        assertThrows(RuntimeException.class,()->view(3,1,counts(),List.of(request("fictional-a"),request("fictional-a"))));
        var request=request("fictional-a");var foreign=new SourceDayRunRegistration.Request(day,"d".repeat(64),registered,request.source(),request.runId());assertThrows(RuntimeException.class,()->view(3,1,counts(),List.of(foreign)));
        var noAgent=counts();noAgent.put("agent",0L);assertThrows(RuntimeException.class,()->view(1,1,noAgent,List.of(request)));
    }
    @Test void actualZeroAndUnknownAreDifferentAndTimeHashCapacityFailClosed(){
        var empty=Map.of("news_tech",0L,"agent",0L,"skill",0L,"workflow",0L,"model",0L);assertEquals(0,view(0,0,empty,List.of()).sourceCount());assertNull(((SourceDayCollectionAdmission.Api)d->null).read(day));
        assertThrows(RuntimeException.class,()->new SourceDayCollectionAdmission.SealedDay(day,directoryHash,null,registered,sealed,0,0,empty,List.of()));
        assertThrows(RuntimeException.class,()->new SourceDayCollectionAdmission.SealedDay(day,directoryHash,bundleHash,registered,registered.minusNanos(1000),0,0,empty,List.of()));
        assertThrows(RuntimeException.class,()->new SourceDayCollectionAdmission.SealedDay(day,directoryHash,bundleHash,registered,Instant.parse("2026-09-20T20:30:00.000000Z"),0,0,empty,List.of()));
        assertThrows(SourceDayRoster.CapacityExceeded.class,()->view(10001,10001,empty,List.of()));
    }
}
