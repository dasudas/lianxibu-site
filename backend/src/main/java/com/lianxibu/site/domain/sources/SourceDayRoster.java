package com.lianxibu.site.domain.sources;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Whole-directory raw metadata. Presence and lifecycle values are not admission assertions. */
public final class SourceDayRoster {
    public static final int MAX_SOURCES=10000, MAX_METADATA_BYTES=1048576;
    private static final long MAX_SAFE=9007199254740991L;
    private static final byte[] HASH_PREFIX="LX:SOURCE_DAY_ROSTER:1\n".getBytes(StandardCharsets.UTF_8);
    private static final Set<String> CATEGORIES=Set.of("news_tech","agent","skill","workflow","model");
    public static final class CapacityExceeded extends RuntimeException {
        public CapacityExceeded(){super("source day roster capacity exceeded");}
    }
    public record RawSource(String id,String category,long sourceRevision,long configRevision,
                            String activationStage,String legacyState,Instant configValidUntil,
                            boolean draftPresent,boolean effectivePresent,String effectiveConfigHash) {
        public RawSource {
            SourceDayRoster.id(id);if(category!=null&&!CATEGORIES.contains(category))throw invalid();
            safe(sourceRevision);safe(configRevision);word(activationStage);word(legacyState);
            if(configValidUntil!=null)time(configValidUntil);
            if(effectivePresent!=(effectiveConfigHash!=null)||effectiveConfigHash!=null&&!effectiveConfigHash.matches("[0-9a-f]{64}"))throw invalid();
        }
        @Override public String toString(){return "SourceDayRosterSource[redacted]";}
    }
    /** Null means actual acquisition is unavailable. A verified zero count is an Evidence value. */
    @FunctionalInterface public interface Api {Evidence read();}
    public record Evidence(Instant capturedAt,long sourceCount,List<RawSource> sources) {
        public Evidence {
            time(capturedAt);safe(sourceCount);Objects.requireNonNull(sources);
            if(sourceCount>MAX_SOURCES||sources.size()>MAX_SOURCES)throw new CapacityExceeded();
            if(sourceCount!=sources.size())throw invalid();
            sources=sources.stream().map(Objects::requireNonNull).sorted(Comparator.comparing(RawSource::id)).toList();
            var ids=new HashSet<String>();for(var source:sources)if(!ids.add(source.id()))throw invalid();
            checkedBytes(metadata(capturedAt,sourceCount,sources));
        }
        public String metadataJson(){return new String(checkedBytes(metadata(capturedAt,sourceCount,sources)),StandardCharsets.UTF_8);}
        public String hash(){
            try{var hash=MessageDigest.getInstance("SHA-256");hash.update(HASH_PREFIX);return HexFormat.of().formatHex(hash.digest(checkedBytes(metadata(capturedAt,sourceCount,sources))));}
            catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 unavailable",impossible);}
        }
        @Override public String toString(){return "SourceDayRosterEvidence[redacted]";}
    }
    private static ObjectNode metadata(Instant captured,long count,List<RawSource> sources){
        var node=JsonMapper.shared().createObjectNode().put("captured_at",stamp(captured)).put("schema_version","source-day-roster-raw-v1").put("source_count",count);
        var array=node.putArray("sources");
        for(var source:sources){
            var item=array.addObject().put("activation_stage",source.activationStage());
            if(source.category()==null)item.putNull("category");else item.put("category",source.category());
            item.put("config_revision",source.configRevision());
            if(source.configValidUntil()==null)item.putNull("config_valid_until");else item.put("config_valid_until",stamp(source.configValidUntil()));
            item.put("draft_present",source.draftPresent());
            if(source.effectiveConfigHash()==null)item.putNull("effective_config_hash");else item.put("effective_config_hash",source.effectiveConfigHash());
            item.put("effective_present",source.effectivePresent()).put("id",source.id()).put("legacy_state",source.legacyState()).put("source_revision",source.sourceRevision());
        }
        return node;
    }
    // The closed schema inserts every object field in lexical order; all numbers are safe integers.
    private static byte[] checkedBytes(ObjectNode node){byte[] bytes=JsonMapper.shared().writeValueAsBytes(node);if(bytes.length>MAX_METADATA_BYTES)throw new CapacityExceeded();return bytes;}
    private static void safe(long value){if(value<0||value>MAX_SAFE)throw invalid();}
    private static void id(String value){
        if(value==null||value.codePointCount(0,value.length())<1||value.codePointCount(0,value.length())>80||value.getBytes(StandardCharsets.UTF_8).length>320
                ||value.codePoints().anyMatch(Character::isISOControl))throw invalid();
        for(int i=0;i<value.length();i++){char unit=value.charAt(i);if(Character.isHighSurrogate(unit)){if(++i>=value.length()||!Character.isLowSurrogate(value.charAt(i)))throw invalid();}
            else if(Character.isLowSurrogate(unit))throw invalid();}
    }
    private static void word(String value){if(value==null||!value.matches("[a-z][a-z0-9_]{0,31}"))throw invalid();}
    private static void time(Instant value){
        if(value==null||value.getEpochSecond()<0||value.getNano()%1000!=0)throw invalid();
        try{safe(Math.addExact(Math.multiplyExact(value.getEpochSecond(),1000000L),value.getNano()/1000L));}catch(ArithmeticException overflow){throw invalid();}
    }
    private static String stamp(Instant value){return new DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(value);}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("source day roster raw metadata invalid");}
    private SourceDayRoster(){ }
}
