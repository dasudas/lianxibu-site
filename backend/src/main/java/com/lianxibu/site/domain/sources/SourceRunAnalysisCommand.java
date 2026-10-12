package com.lianxibu.site.domain.sources;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Internal projection command; its jobs caller must prove the complete original final output. */
public record SourceRunAnalysisCommand(String runId,String sourceId,LocalDate businessDate,long collectionGeneration,String manifestHash,long accepted,long excluded,
        Position position,String dedupSummary,String analysisSummary,String coverageState){
    public record Position(String ownerId,String logicalKey,long generation,Instant inputCutoff,String resultHash,String receiptId){
        public Position {id(ownerId,64);id(receiptId,64);if(logicalKey==null||logicalKey.isBlank()||logicalKey.length()>4096||generation<1||generation>9_007_199_254_740_991L||inputCutoff==null||inputCutoff.getNano()%1000!=0)throw invalid();hash(resultHash);}
        @Override public String toString(){return "SourceRunAnalysisPosition[redacted]";}
    }
    public SourceRunAnalysisCommand {
        id(runId,64);id(sourceId,80);Objects.requireNonNull(position);hash(manifestHash);
        if(businessDate==null||collectionGeneration<1||collectionGeneration>9_007_199_254_740_991L||accepted<0||excluded<0||accepted>MemberUniverse.MAX_MEMBERS||excluded>MemberUniverse.MAX_MEMBERS||accepted+excluded>MemberUniverse.MAX_MEMBERS||position.inputCutoff().isBefore(businessDate.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant())||!Set.of("complete","partial").contains(coverageState))throw invalid();summary(dedupSummary);summary(analysisSummary);
    }
    private static void summary(String text){if(text==null||text.getBytes(StandardCharsets.UTF_8).length>16*1024*1024-1)throw invalid();}
    private static void hash(String value){if(value==null||!value.matches("[0-9a-f]{64}"))throw invalid();}
    private static void id(String value,int maximum){if(value==null||!value.matches("[A-Za-z0-9._:-]{1,"+maximum+"}"))throw invalid();}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("source analysis projection command rejected");}
    @Override public String toString(){return "SourceRunAnalysisCommand[redacted]";}
}
