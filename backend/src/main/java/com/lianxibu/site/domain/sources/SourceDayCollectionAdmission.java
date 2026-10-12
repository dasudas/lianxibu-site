package com.lianxibu.site.domain.sources;

import java.time.*;
import java.util.*;

/** Trusted provider view of an original full seal; actual run receipts remain mandatory. */
public final class SourceDayCollectionAdmission {
    private static final Set<String> CATEGORIES=Set.of("news_tech","agent","skill","workflow","model");
    public record SealedDay(LocalDate day,String directoryHash,String bundleHash,Instant registeredAt,Instant sealedAt,
                            long sourceCount,long unassignedCount,Map<String,Long> cohortCounts,List<SourceDayRunRegistration.Request> requests){
        public SealedDay {
            hash(directoryHash);hash(bundleHash);window(day,registeredAt);window(day,sealedAt);if(sealedAt.isBefore(registeredAt))throw invalid();
            Objects.requireNonNull(cohortCounts);Objects.requireNonNull(requests);if(sourceCount>SourceDayRoster.MAX_SOURCES||requests.size()>SourceDayRoster.MAX_SOURCES)throw new SourceDayRoster.CapacityExceeded();
            if(sourceCount<0||unassignedCount<0||unassignedCount>sourceCount||!cohortCounts.keySet().equals(CATEGORIES))throw invalid();
            long assigned=0;for(var count:cohortCounts.values()){if(count==null||count<0||count>sourceCount)throw invalid();assigned=Math.addExact(assigned,count);}
            if(assigned+unassignedCount!=sourceCount)throw invalid();var sourceIds=new HashSet<String>();var runIds=new HashSet<String>();var configured=new HashMap<String,Long>();
            for(var request:requests){Objects.requireNonNull(request);String category=request.source().category();
                if(!day.equals(request.day())||!directoryHash.equals(request.rosterHash())||!registeredAt.equals(request.registeredAt())||!sourceIds.add(request.source().id())||!runIds.add(request.runId()))throw invalid();
                long count=configured.merge(category,1L,Long::sum);if(!cohortCounts.containsKey(category)||count>cohortCounts.get(category))throw invalid();
            }
            cohortCounts=Map.copyOf(cohortCounts);requests=List.copyOf(requests);
        }
        @Override public String toString(){return "OriginalSourceDayCollectionView[redacted]";}
    }
    /** Owns its read transaction. Null means absent or unverified; never an empty successful day. */
    @FunctionalInterface public interface Api {SealedDay read(LocalDate day);}
    private static void hash(String value){if(value==null||!value.matches("[0-9a-f]{64}"))throw invalid();}
    private static void window(LocalDate day,Instant value){if(day==null||day.getYear()<1970||day.getYear()>9999||value==null||value.getNano()%1000!=0||value.isBefore(day.plusDays(1).atTime(0,10).atZone(ZoneId.of("Asia/Shanghai")).toInstant())||!value.isBefore(day.plusDays(1).atTime(4,30).atZone(ZoneId.of("Asia/Shanghai")).toInstant()))throw invalid();}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("invalid original source day collection view");}
    private SourceDayCollectionAdmission(){ }
}
