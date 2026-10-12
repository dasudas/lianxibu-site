package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class FrozenCatalogSourceFinalTest {
    CatalogSourceFinalResult result(int count,String summary,boolean collectionGaps){var f=new CatalogSourceFinalResultTest();var x=f.fixture("agent",count,summary,List.of());return f.result(x);}
    @Test void fullOriginalMultibyteResultRoundTripsAcrossBlocksAndKeepsTheTail(){
        var original=result(501,"😀".repeat(8000),false);var encoded=FrozenCatalogSourceFinal.encode(original);assertTrue(encoded.payloads().size()>2);assertEquals(original.json(),FrozenCatalogSourceFinal.decode(encoded.reference(),encoded.payloads(),original).json());assertTrue(original.summary().endsWith("tail-2"));assertEquals(original.hash(),encoded.reference().resultHash());assertEquals(encoded.reference(),FrozenCatalogSourceFinal.Reference.fromJson(encoded.reference().json()));assertThrows(UnsupportedOperationException.class,()->encoded.payloads().clear());assertFalse(encoded.toString().contains("😀"));
    }
    @Test void emptyAndCollectorGapsKeepTheirExactOriginalBytesAndSmallCapacityFailsWhole(){
        for(int count:List.of(0,5)){var original=result(count,"Fictional complete summary",true);var encoded=FrozenCatalogSourceFinal.encode(original);assertSame(original,FrozenCatalogSourceFinal.decode(encoded.reference(),encoded.payloads(),original));assertThrows(GenerationFlowService.CapacityGuarded.class,()->FrozenCatalogSourceFinal.encodeWithinCapacity(original,100));}
    }
    @Test void missingExtraChangedBlocksCrossOwnerAndRehashedIndexCannotSubstituteOriginal(){
        var original=result(5,"Fictional summary",false);var encoded=FrozenCatalogSourceFinal.encode(original);var ref=encoded.reference();var missing=new ArrayList<>(encoded.payloads());missing.removeLast();assertThrows(RuntimeException.class,()->FrozenCatalogSourceFinal.decode(ref,missing,original));var extra=new ArrayList<>(encoded.payloads());extra.add(extra.getLast());assertThrows(RuntimeException.class,()->FrozenCatalogSourceFinal.decode(ref,extra,original));
        assertThrows(RuntimeException.class,()->FrozenCatalogSourceFinal.decode(new FrozenCatalogSourceFinal.Reference("fictional-wrong-owner",ref.resultHash(),ref.indexHash()),encoded.payloads(),original));assertThrows(RuntimeException.class,()->FrozenCatalogSourceFinal.decode(new FrozenCatalogSourceFinal.Reference(ref.ownerId(),"b".repeat(64),ref.indexHash()),encoded.payloads(),original));
        var changed=new ArrayList<>(encoded.payloads());var chunk=(ObjectNode)CanonicalJson.parse(changed.get(1));chunk.put("bytes_base64",Base64.getEncoder().encodeToString("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));changed.set(1,canonical(chunk));var index=(ObjectNode)CanonicalJson.parse(changed.getFirst());((ObjectNode)index.get("parts").get(0)).put("payload_hash",CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,chunk));index.put("byte_count",2);changed.set(0,canonical(index));var rehashed=new FrozenCatalogSourceFinal.Reference(ref.ownerId(),ref.resultHash(),CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,index));assertThrows(RuntimeException.class,()->FrozenCatalogSourceFinal.decode(rehashed,changed,original));
        var unknown=(ObjectNode)CanonicalJson.parse(ref.json());unknown.put("extra",true);assertThrows(RuntimeException.class,()->FrozenCatalogSourceFinal.Reference.fromJson(canonical(unknown)));
    }
    static String canonical(tools.jackson.databind.JsonNode n){return new String(CanonicalJson.bytes(n),java.nio.charset.StandardCharsets.UTF_8);}
}
