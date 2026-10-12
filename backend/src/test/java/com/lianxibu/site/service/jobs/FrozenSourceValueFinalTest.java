package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class FrozenSourceValueFinalTest {
    SourceValueFinalResult result(int count,String summary,boolean partial){var f=new SourceValueFinalResultTest();var x=f.fixture(count,false,true,partial,summary,"Fictional selected evidence",List.of());return f.result(x);}
    @Test void fullOriginalMultibyteResultRoundTripsAcrossBlocksAndKeepsTheTail(){
        var original=result(501,"😀".repeat(12000),false);var encoded=FrozenSourceValueFinal.encode(original);assertTrue(encoded.payloads().size()>2);assertEquals(original.json(),FrozenSourceValueFinal.decode(encoded.reference(),encoded.payloads(),original).json());assertTrue(original.summary().endsWith("tail-2"));assertEquals(original.hash(),encoded.reference().resultHash());assertEquals(encoded.reference(),FrozenSourceValueFinal.Reference.fromJson(encoded.reference().json()));assertThrows(UnsupportedOperationException.class,()->encoded.payloads().clear());assertFalse(encoded.toString().contains("😀"));
    }
    @Test void emptyAndPartialKeepTheirExactOriginalBytesAndSmallCapacityFailsWhole(){
        for(int count:List.of(0,5)){var original=result(count,"Fictional complete summary",true);var encoded=FrozenSourceValueFinal.encode(original);assertSame(original,FrozenSourceValueFinal.decode(encoded.reference(),encoded.payloads(),original));assertThrows(GenerationFlowService.CapacityGuarded.class,()->FrozenSourceValueFinal.encodeWithinCapacity(original,100));}
    }
    @Test void missingExtraChangedBlocksCrossOwnerAndRehashedIndexCannotSubstituteOriginal(){
        var original=result(5,"Fictional summary",false);var encoded=FrozenSourceValueFinal.encode(original);var ref=encoded.reference();var missing=new ArrayList<>(encoded.payloads());missing.removeLast();assertThrows(RuntimeException.class,()->FrozenSourceValueFinal.decode(ref,missing,original));var extra=new ArrayList<>(encoded.payloads());extra.add(extra.getLast());assertThrows(RuntimeException.class,()->FrozenSourceValueFinal.decode(ref,extra,original));
        assertThrows(RuntimeException.class,()->FrozenSourceValueFinal.decode(new FrozenSourceValueFinal.Reference("fictional-wrong-owner",ref.resultHash(),ref.indexHash()),encoded.payloads(),original));assertThrows(RuntimeException.class,()->FrozenSourceValueFinal.decode(new FrozenSourceValueFinal.Reference(ref.ownerId(),"b".repeat(64),ref.indexHash()),encoded.payloads(),original));
        var changed=new ArrayList<>(encoded.payloads());var chunk=(ObjectNode)CanonicalJson.parse(changed.get(1));chunk.put("bytes_base64",Base64.getEncoder().encodeToString("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));changed.set(1,canonical(chunk));var index=(ObjectNode)CanonicalJson.parse(changed.getFirst());((ObjectNode)index.get("parts").get(0)).put("payload_hash",CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,chunk));index.put("byte_count",2);changed.set(0,canonical(index));var rehashed=new FrozenSourceValueFinal.Reference(ref.ownerId(),ref.resultHash(),CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,index));assertThrows(RuntimeException.class,()->FrozenSourceValueFinal.decode(rehashed,changed,original));
        var unknown=(ObjectNode)CanonicalJson.parse(ref.json());unknown.put("extra",true);assertThrows(RuntimeException.class,()->FrozenSourceValueFinal.Reference.fromJson(canonical(unknown)));
    }
    static String canonical(tools.jackson.databind.JsonNode n){return new String(CanonicalJson.bytes(n),java.nio.charset.StandardCharsets.UTF_8);}
}
