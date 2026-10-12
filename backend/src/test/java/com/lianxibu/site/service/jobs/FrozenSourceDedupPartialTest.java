package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class FrozenSourceDedupPartialTest {
    SourceDedupResolvedStageResultTest fixture(){return new SourceDedupResolvedStageResultTest();}
    SourceDedupStageResult partial(SourceDedupResolvedStageResultTest f,int padding)throws Exception {f.setup(201,padding);f.applied(0);f.terminal(1,"failed");f.applied(2);return SourceDedupStageResult.createResolved(f.snapshot(f.ref));}
    @Test void actualSparseProofBytesAndReferenceRoundTripThroughMultipleBoundedChunks()throws Exception {
        var f=fixture();var original=partial(f,900);int count=f.f.db.count("1=1");var archive=FrozenSourceDedupPartial.encode(original);assertTrue(archive.payloads().size()>2);assertEquals(archive,FrozenSourceDedupPartial.encode(original));assertEquals(archive.reference(),FrozenSourceDedupPartial.Reference.fromJson(archive.reference().json()));assertSame(original,FrozenSourceDedupPartial.decode(archive.reference(),archive.payloads(),original));
        for(String part:archive.payloads())assertTrue(part.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=FrozenSourceDedupPartial.ROW_BYTES);assertEquals(original.hash(),archive.reference().resultHash());assertEquals(f.ref.ownerId(),archive.reference().ownerId());assertEquals(count,f.f.db.count("1=1"));assertThrows(UnsupportedOperationException.class,()->archive.payloads().clear());assertFalse(archive.toString().contains("Fictional source"));
    }
    @Test void oldCompleteAndZeroBytesNeverGrantAPartialArchive()throws Exception {
        for(int count:List.of(1,0)){var f=fixture();f.setup(count);if(count>0)f.applied(0);var original=SourceDedupStageResult.createResolved(f.snapshot(f.ref));assertThrows(RuntimeException.class,()->FrozenSourceDedupPartial.encode(original));}
        assertThrows(RuntimeException.class,()->FrozenSourceDedupPartial.encode(null));
    }
    @Test void missingExtraReorderedAndRehashedModifiedBytesAreRejected()throws Exception {
        var f=fixture();var original=partial(f,900);var archive=FrozenSourceDedupPartial.encode(original);for(String mode:List.of("missing","extra","reordered","index_extra","chunk_extra","part_number","base64","short_nonfinal","rehashed_json")){
            var parts=new ArrayList<>(archive.payloads());var index=(ObjectNode)CanonicalJson.parse(parts.getFirst());var chunk=(ObjectNode)CanonicalJson.parse(parts.get(1));switch(mode){
                case "missing"->parts.remove(parts.size()-1);case "extra"->parts.add(parts.getLast());case "reordered"->Collections.swap(parts,1,2);
                case "index_extra"->index.put("extra",true);case "chunk_extra"->{chunk.put("extra",true);parts.set(1,JdbcFrozenInputs.canonical(chunk));}
                case "part_number"->((ObjectNode)index.get("parts").get(0)).put("part_no",2);
                case "base64"->{chunk.put("bytes_base64","??");parts.set(1,JdbcFrozenInputs.canonical(chunk));}
                case "short_nonfinal"->{chunk.put("bytes_base64",Base64.getEncoder().encodeToString("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));parts.set(1,JdbcFrozenInputs.canonical(chunk));}
                case "rehashed_json"->{byte[] raw=Base64.getDecoder().decode(chunk.get("bytes_base64").asText());raw[0]='[';chunk.put("bytes_base64",Base64.getEncoder().encodeToString(raw));parts.set(1,JdbcFrozenInputs.canonical(chunk));}
            }
            if(!mode.equals("missing")&&!mode.equals("extra")&&!mode.equals("reordered")){((ObjectNode)index.get("parts").get(0)).put("payload_hash",CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,CanonicalJson.parse(parts.get(1))));parts.set(0,JdbcFrozenInputs.canonical(index));}
            var ref=new FrozenSourceDedupPartial.Reference(archive.reference().ownerId(),archive.reference().resultHash(),CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,CanonicalJson.parse(parts.getFirst())));assertThrows(RuntimeException.class,()->FrozenSourceDedupPartial.decode(ref,parts,original),mode);
        }
    }
    @Test void nativeChangedResolutionNewProofWrongIdentityAndCapacityFailWithoutWriting()throws Exception {
        var f=fixture();var original=partial(f,0);var archive=FrozenSourceDedupPartial.encode(original);f.terminal(1,"cancelled");var changed=SourceDedupStageResult.createResolved(f.snapshot(f.ref));assertThrows(RuntimeException.class,()->FrozenSourceDedupPartial.decode(archive.reference(),archive.payloads(),changed));
        int count=f.f.db.count("1=1");for(var ref:List.of(new FrozenSourceDedupPartial.Reference("fictional-other",original.hash(),archive.reference().indexHash()),new FrozenSourceDedupPartial.Reference(archive.reference().ownerId(),"a".repeat(64),archive.reference().indexHash()),new FrozenSourceDedupPartial.Reference(archive.reference().ownerId(),original.hash(),"b".repeat(64))))assertThrows(RuntimeException.class,()->FrozenSourceDedupPartial.decode(ref,archive.payloads(),original));
        assertThrows(GenerationFlowService.CapacityGuarded.class,()->FrozenSourceDedupPartial.encodeWithinCapacity(original,64));assertThrows(RuntimeException.class,()->FrozenSourceDedupPartial.encodeWithinCapacity(original,0));assertThrows(RuntimeException.class,()->FrozenSourceDedupPartial.Reference.fromJson("{\"unknown\":true}"));assertThrows(RuntimeException.class,()->new FrozenSourceDedupPartial.Reference("fictional-other","x","y"));assertEquals(count,f.f.db.count("1=1"));
    }
}
