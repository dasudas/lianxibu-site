package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.sources.MemberUniverse;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class FrozenSourceUniverseTest {
    static final String OWNER="fictional-universe-owner", RUN="fictional-universe-run", HASH="a".repeat(64);
    static MemberUniverse.Universe universe(int count, boolean large) {
        var parts=new ArrayList<MemberUniverse.Part>();var members=new ArrayList<MemberUniverse.Member>();
        for(int n=0;n<count;n++) {
            String suffix=String.format(Locale.ROOT,"%05d",n);
            members.add(new MemberUniverse.Member("fictional-item-"+suffix,"fictional-member-"+suffix,
                    large?"😀".repeat(1000)+suffix:"fictional-key-"+suffix,"fictional-observation-"+suffix,
                    HASH,null,n/100,n%2==0?"accepted":"excluded"));
        }
        for(int n=0;n<(count+99)/100;n++)parts.add(new MemberUniverse.Part(n,HASH,Math.min(100,count-n*100)));
        return new MemberUniverse.Universe(RUN,"fictional-source",HASH,parts,members,(count+1)/2,count/2);
    }
    @Test void completeUnicodeUniverseRoundTripsAcrossChunksWithCompactBoundReference() {
        var original=universe(201,true);var frozen=FrozenSourceUniverse.encode(OWNER,original);
        assertTrue(frozen.payloads().size()>2);assertTrue(frozen.reference().json().length()<512);
        assertEquals(original,FrozenSourceUniverse.decode(frozen.reference(),frozen.payloads()));
        assertEquals(frozen,FrozenSourceUniverse.encode(OWNER,original));
        assertEquals(frozen.reference(),FrozenSourceUniverse.Reference.fromJson(frozen.reference().json()));
        var other=FrozenSourceUniverse.encode("fictional-other-owner",original);
        assertEquals(frozen.reference().universeHash(),other.reference().universeHash());
        assertNotEquals(frozen.reference().indexHash(),other.reference().indexHash());
        assertThrows(RuntimeException.class,()->FrozenSourceUniverse.decode(other.reference(),frozen.payloads()));
        assertFalse(frozen.toString().contains("fictional-key"));
    }
    @Test void emptyUniverseStillHasAnOriginalProofAndCounts() {
        var frozen=FrozenSourceUniverse.encode(OWNER,universe(0,false));
        assertEquals(2,frozen.payloads().size());assertEquals(universe(0,false),FrozenSourceUniverse.decode(frozen.reference(),frozen.payloads()));
        assertThrows(RuntimeException.class,()->FrozenSourceUniverse.decode(frozen.reference(),List.of()));
    }
    @Test void missingSurplusReorderedAndChangedChunksCannotReplaceFrozenUniverse() {
        var frozen=FrozenSourceUniverse.encode(OWNER,universe(101,true));var parts=new ArrayList<>(frozen.payloads());
        parts.removeLast();assertThrows(RuntimeException.class,()->FrozenSourceUniverse.decode(frozen.reference(),parts));
        parts.clear();parts.addAll(frozen.payloads());parts.add(parts.getLast());
        assertThrows(RuntimeException.class,()->FrozenSourceUniverse.decode(frozen.reference(),parts));
        parts.clear();parts.addAll(frozen.payloads());Collections.swap(parts,1,2);
        assertThrows(RuntimeException.class,()->FrozenSourceUniverse.decode(frozen.reference(),parts));
        parts.clear();parts.addAll(frozen.payloads());parts.set(1,"{\"schema_version\":\"source-universe-chunk-v1\",\"bytes_base64\":\"e30=\"}");
        assertThrows(RuntimeException.class,()->FrozenSourceUniverse.decode(frozen.reference(),parts));
    }
    @Test void rehashedDifferentMembershipIsNotTheOriginalExternalReference() {
        var frozen=FrozenSourceUniverse.encode(OWNER,universe(2,false));var parts=new ArrayList<>(frozen.payloads());
        var chunk=(ObjectNode)CanonicalJson.parse(parts.get(1));
        String body=new String(Base64.getDecoder().decode(chunk.path("bytes_base64").asText()),java.nio.charset.StandardCharsets.UTF_8);
        chunk.put("bytes_base64",Base64.getEncoder().encodeToString(body.replace("fictional-observation-00000","fictional-observation-99999").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var index=(ObjectNode)CanonicalJson.parse(parts.getFirst());
        ((ObjectNode)index.path("parts").get(0)).put("payload_hash",CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,chunk));
        parts.set(0,JdbcText.canonical(index));parts.set(1,JdbcText.canonical(chunk));
        assertThrows(RuntimeException.class,()->FrozenSourceUniverse.decode(frozen.reference(),parts));
    }
    @Test void closedIndexReferenceAndCapacityFailBeforeUnboundedDecode() {
        var frozen=FrozenSourceUniverse.encode(OWNER,universe(1,false));var parts=new ArrayList<>(frozen.payloads());
        var index=(ObjectNode)CanonicalJson.parse(parts.getFirst());index.put("unexpected",true);parts.set(0,JdbcText.canonical(index));
        assertThrows(RuntimeException.class,()->FrozenSourceUniverse.decode(frozen.reference(),parts));
        assertThrows(RuntimeException.class,()->FrozenSourceUniverse.Reference.fromJson(frozen.reference().json().replace("\"run_id\"","\"extra\":true,\"run_id\"")));
        assertThrows(RuntimeException.class,()->FrozenSourceUniverse.decode(frozen.reference(),Collections.nCopies(FrozenSourceUniverse.MAX_CHUNKS+2,"{}")));
        assertThrows(RuntimeException.class,()->FrozenSourceUniverse.decode(frozen.reference(),List.of(" ".repeat(65537),"{}")));
    }
    static final class JdbcText { static String canonical(tools.jackson.databind.JsonNode value){return new String(CanonicalJson.bytes(value),java.nio.charset.StandardCharsets.UTF_8);} }
}
