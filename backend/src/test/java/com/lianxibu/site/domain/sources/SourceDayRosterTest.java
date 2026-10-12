package com.lianxibu.site.domain.sources;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class SourceDayRosterTest {
    private static final Instant NOW=Instant.parse("2026-09-20T16:10:00.000001Z");
    private static final String HASH="a".repeat(64);
    private static SourceDayRoster.RawSource source(String id,String category){
        return new SourceDayRoster.RawSource(id,category,0,3,"draft","disabled",null,false,false,null);
    }
    @Test void preservesEveryUnassignedSourceAndStableImmutableFullDirectory() {
        var input=new ArrayList<>(List.of(source("fictional-b",null),source("fictional-a",null)));
        var value=new SourceDayRoster.Evidence(NOW,2,input);input.clear();
        assertEquals(2,value.sourceCount());assertEquals(List.of("fictional-a","fictional-b"),value.sources().stream().map(SourceDayRoster.RawSource::id).toList());
        assertTrue(value.sources().stream().allMatch(s->s.category()==null));
        assertThrows(UnsupportedOperationException.class,()->value.sources().clear());
        assertEquals(value.hash(),new SourceDayRoster.Evidence(NOW,2,List.of(source("fictional-b",null),source("fictional-a",null))).hash());
        assertTrue(value.metadataJson().contains("source-day-roster-raw-v1"));assertTrue(value.metadataJson().contains("\"category\":null"));
        assertEquals("SourceDayRosterEvidence[redacted]",value.toString());assertEquals("SourceDayRosterSource[redacted]",value.sources().getFirst().toString());
    }
    @Test void actualZeroIsDistinctFromUnknownAndCountMismatchIsNeverComplete() {
        SourceDayRoster.Api unknown=()->null;assertNull(unknown.read());
        var empty=new SourceDayRoster.Evidence(NOW,0,List.of());assertTrue(empty.sources().isEmpty());assertEquals(0,empty.sourceCount());
        assertThrows(RuntimeException.class,()->new SourceDayRoster.Evidence(NOW,0,List.of(source("fictional",null))));
        assertThrows(RuntimeException.class,()->new SourceDayRoster.Evidence(NOW,1,List.of()));
        assertThrows(RuntimeException.class,()->new SourceDayRoster.Evidence(NOW,2,List.of(source("same",null),source("same",null))));
    }
    @Test void configurationPresenceHashAndRawValidityAreIndependentFromAdmission() {
        var expired=new SourceDayRoster.RawSource("fictional","news_tech",4,3,"production","enabled",NOW.minusSeconds(1),true,true,HASH);
        assertEquals(NOW.minusSeconds(1),expired.configValidUntil());assertEquals(HASH,expired.effectiveConfigHash());
        // Missing production configuration remains an actual raw fact; no healthy or admission flag is manufactured.
        var incomplete=new SourceDayRoster.RawSource("fictional",null,4,3,"production","enabled",null,false,false,null);
        assertFalse(incomplete.effectivePresent());
        assertThrows(RuntimeException.class,()->new SourceDayRoster.RawSource("fictional",null,0,0,"draft","disabled",null,false,true,null));
        assertThrows(RuntimeException.class,()->new SourceDayRoster.RawSource("fictional",null,0,0,"draft","disabled",null,false,false,HASH));
        assertThrows(RuntimeException.class,()->new SourceDayRoster.RawSource("fictional",null,0,0,"draft","disabled",null,false,true,"A".repeat(64)));
    }
    @Test void rawIdentityCategoryWordsRevisionAndTimeMustBeValid() {
        for(String category:List.of("news_tech","agent","skill","workflow","model"))assertEquals(category,source("fictional",category).category());
        assertThrows(RuntimeException.class,()->source("",null));assertThrows(RuntimeException.class,()->source("fictional","guessed-category"));
        for(long bad:List.of(-1L,9007199254740992L)){
            assertThrows(RuntimeException.class,()->new SourceDayRoster.RawSource("fictional",null,bad,0,"draft","disabled",null,false,false,null));
            assertThrows(RuntimeException.class,()->new SourceDayRoster.RawSource("fictional",null,0,bad,"draft","disabled",null,false,false,null));
        }
        assertThrows(RuntimeException.class,()->new SourceDayRoster.RawSource("fictional",null,0,0,"bad\nstate","disabled",null,false,false,null));
        assertThrows(RuntimeException.class,()->new SourceDayRoster.RawSource("fictional",null,0,0,"draft","",null,false,false,null));
        assertThrows(RuntimeException.class,()->new SourceDayRoster.Evidence(NOW.plusNanos(1),0,List.of()));
        assertThrows(RuntimeException.class,()->new SourceDayRoster.Evidence(Instant.ofEpochSecond(-1),0,List.of()));
        assertThrows(RuntimeException.class,()->new SourceDayRoster.RawSource("fictional",null,0,0,"draft","disabled",NOW.plusNanos(1),false,false,null));
    }
    @Test void metadataHashBindsActualConfigurationRevisionValidityAndCaptureTime() {
        var original=new SourceDayRoster.Evidence(NOW,1,List.of(source("fictional",null)));
        assertNotEquals(original.hash(),new SourceDayRoster.Evidence(NOW.plusNanos(1000),1,original.sources()).hash());
        assertNotEquals(original.hash(),new SourceDayRoster.Evidence(NOW,1,List.of(source("fictional","agent"))).hash());
        var updated=new SourceDayRoster.RawSource("fictional",null,1,4,"production","enabled",NOW.plusSeconds(10),true,true,HASH);
        assertNotEquals(original.hash(),new SourceDayRoster.Evidence(NOW,1,List.of(updated)).hash());
    }
    @Test void excessiveDirectoryOrMetadataRaisesCapacityInsteadOfTruncating() {
        assertThrows(SourceDayRoster.CapacityExceeded.class,()->new SourceDayRoster.Evidence(NOW,10001,List.of()));
        var large=new ArrayList<SourceDayRoster.RawSource>();
        for(int i=0;i<3000;i++)large.add(source("🟣".repeat(76)+String.format(Locale.ROOT,"%04d",i),null));
        assertThrows(SourceDayRoster.CapacityExceeded.class,()->new SourceDayRoster.Evidence(NOW,large.size(),large));
    }
    @Test void closedMetadataUsesCanonicalEscapingAndIndependentHashNamespace() throws Exception {
        var value=new SourceDayRoster.Evidence(NOW,1,List.of(source("fictional-\"\\🟣",null)));
        byte[] bytes=value.metadataJson().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertArrayEquals(bytes,com.lianxibu.site.domain.protocol.CanonicalJson.bytes(com.lianxibu.site.domain.protocol.CanonicalJson.parse(value.metadataJson())));
        var hash=java.security.MessageDigest.getInstance("SHA-256");hash.update("LX:SOURCE_DAY_ROSTER:1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(HexFormat.of().formatHex(hash.digest(bytes)),value.hash());
        assertThrows(RuntimeException.class,()->source("fictional-\uD800",null));
    }
}
