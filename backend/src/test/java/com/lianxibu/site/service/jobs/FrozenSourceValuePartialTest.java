package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.repository.jobs.JdbcFrozenInputs;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/** Pure codec checked against actual original resolved P2 output in owned databases. */
class FrozenSourceValuePartialTest {
    GenerationJdbcStoreTest database(){return new GenerationJdbcStoreTest();}
    SourceValuePartialResolutionTest fixture(boolean mixed)throws Exception{var f=new SourceValuePartialResolutionTest(){@Override GenerationJdbcStoreTest database(){return FrozenSourceValuePartialTest.this.database();}};f.setup(mixed);return f;}
    SourceValueStageResult partial(SourceValuePartialResolutionTest f){for(int n=0;n<f.f.plan.chunks().size();n++)f.prepare(n);f.db.jdbc.update("UPDATE jobs SET state='failed',call_count=3,revision=revision+1 WHERE id=?",f.f.plan.chunks().getFirst().rootId());for(int n=1;n<f.f.plan.chunks().size();n++)f.outcomes.complete(n);return SourceValueStageResult.createResolved(f.read());}
    @Test void OriginalSparseValueOutputAndTypedOmissionsRoundTripThroughBoundedBlocks()throws Exception{
        var f=fixture(true);var original=partial(f);int count=f.db.count("1=1"),calls=f.db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class);var archive=FrozenSourceValuePartial.encode(original);assertTrue(archive.payloads().size()>2);assertEquals(archive,FrozenSourceValuePartial.encode(original));assertEquals(archive.reference(),FrozenSourceValuePartial.Reference.fromJson(archive.reference().json()));assertSame(original,FrozenSourceValuePartial.decode(archive.reference(),archive.payloads(),original));assertEquals(original.planRef().ownerId(),archive.reference().ownerId());assertEquals(original.hash(),archive.reference().resultHash());for(String payload:archive.payloads())assertTrue(payload.getBytes(StandardCharsets.UTF_8).length<=FrozenSourceValuePartial.ROW_BYTES);assertEquals(count,f.db.count("1=1"));assertEquals(calls,f.db.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));assertThrows(UnsupportedOperationException.class,()->archive.payloads().clear());assertFalse(archive.toString().contains("Fictional"));
    }
    @Test void FailedDedupZeroValueIsRetainedButLegacyCompleteAndZeroCannotUsePartialSchema()throws Exception{
        var f=fixture(false);var original=SourceValueStageResult.createResolved(f.read());var archive=FrozenSourceValuePartial.encode(original);assertSame(original,FrozenSourceValuePartial.decode(archive.reference(),archive.payloads(),original));assertEquals(1,CanonicalJson.parse(original.json()).get("accepted").asInt());assertEquals(1,CanonicalJson.parse(original.json()).get("missing").size());assertTrue(original.fragments().isEmpty());
        var pure=new SourceValueStageResultTest();for(int members:List.of(0,5)){var x=pure.f.fixture(members);var plan=pure.plan(x);var legacy=SourceValueStageResult.create(plan,x.universe(),pure.ref(plan,x.universe()),pure.proofs(plan,x.universe()));assertThrows(RuntimeException.class,()->FrozenSourceValuePartial.encode(legacy));}assertThrows(RuntimeException.class,()->FrozenSourceValuePartial.encode(null));assertThrows(RuntimeException.class,()->FrozenSourceValuePartial.Reference.fromJson(archive.reference().json().replace("source-value-partial-ref-v1","source-dedup-partial-ref-v1")));
    }
    @Test void MissingExtraReorderedAndSelfRehashedDamagedOriginalBytesAreRejected()throws Exception{
        var f=fixture(true);var original=partial(f);var archive=FrozenSourceValuePartial.encode(original);assertTrue(archive.payloads().size()>2);
        for(String mode:List.of("missing","extra","reordered","index_extra","chunk_extra","part_number","base64","noncanonical_base64","short_nonfinal","rehashed_json")){
            var parts=new ArrayList<>(archive.payloads());var index=(ObjectNode)CanonicalJson.parse(parts.getFirst());var chunk=(ObjectNode)CanonicalJson.parse(parts.get(1));
            switch(mode){
                case "missing"->parts.remove(parts.size()-1);case "extra"->parts.add(parts.getLast());case "reordered"->Collections.swap(parts,1,2);case "index_extra"->index.put("extra",true);
                case "chunk_extra"->{chunk.put("extra",true);parts.set(1,JdbcFrozenInputs.canonical(chunk));}case "part_number"->((ObjectNode)index.get("parts").get(0)).put("part_no",2);
                case "base64"->{chunk.put("bytes_base64","??");parts.set(1,JdbcFrozenInputs.canonical(chunk));}
                case "noncanonical_base64"->{chunk.put("bytes_base64",chunk.get("bytes_base64").asText().replace("=",""));parts.set(1,JdbcFrozenInputs.canonical(chunk));}
                case "short_nonfinal"->{chunk.put("bytes_base64",Base64.getEncoder().encodeToString("{}".getBytes(StandardCharsets.UTF_8)));parts.set(1,JdbcFrozenInputs.canonical(chunk));}
                case "rehashed_json"->{byte[] raw=Base64.getDecoder().decode(chunk.get("bytes_base64").asText());raw[0]='[';chunk.put("bytes_base64",Base64.getEncoder().encodeToString(raw));parts.set(1,JdbcFrozenInputs.canonical(chunk));}
            }
            if(!Set.of("missing","extra","reordered").contains(mode)){((ObjectNode)index.get("parts").get(0)).put("payload_hash",CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,CanonicalJson.parse(parts.get(1))));parts.set(0,JdbcFrozenInputs.canonical(index));}
            var ref=new FrozenSourceValuePartial.Reference(archive.reference().ownerId(),archive.reference().resultHash(),CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,CanonicalJson.parse(parts.getFirst())));assertThrows(RuntimeException.class,()->FrozenSourceValuePartial.decode(ref,parts,original),mode);
        }
    }
    @Test void DifferentActualResolutionIdentityOrCapacityCannotRewriteOriginalArchive()throws Exception{
        var f=fixture(true);var original=partial(f);var archive=FrozenSourceValuePartial.encode(original);f.db.jdbc.update("UPDATE jobs SET state='cancelled',call_count=0,revision=revision+1 WHERE id=?",f.f.plan.chunks().getFirst().rootId());var changed=SourceValueStageResult.createResolved(f.read());assertThrows(RuntimeException.class,()->FrozenSourceValuePartial.decode(archive.reference(),archive.payloads(),changed));int count=f.db.count("1=1");for(var ref:List.of(new FrozenSourceValuePartial.Reference("fictional-other",original.hash(),archive.reference().indexHash()),new FrozenSourceValuePartial.Reference(archive.reference().ownerId(),"a".repeat(64),archive.reference().indexHash()),new FrozenSourceValuePartial.Reference(archive.reference().ownerId(),original.hash(),"b".repeat(64))))assertThrows(RuntimeException.class,()->FrozenSourceValuePartial.decode(ref,archive.payloads(),original));assertThrows(GenerationFlowService.CapacityGuarded.class,()->FrozenSourceValuePartial.encodeWithinCapacity(original,64));assertThrows(RuntimeException.class,()->FrozenSourceValuePartial.encodeWithinCapacity(original,0));assertThrows(RuntimeException.class,()->FrozenSourceValuePartial.Reference.fromJson("{\"unknown\":true}"));assertThrows(RuntimeException.class,()->new FrozenSourceValuePartial.Reference("fictional-other","x","y"));assertEquals(count,f.db.count("1=1"));
    }
}
