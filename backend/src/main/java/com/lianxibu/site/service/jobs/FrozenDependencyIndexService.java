package com.lianxibu.site.service.jobs;
import com.lianxibu.site.domain.protocol.*;
import com.lianxibu.site.repository.jobs.*;
import com.lianxibu.site.service.jobs.GenerationFlowService.*;
import java.time.Instant;
import java.util.*;

/** Original facts only. Current validity and dispatch authorization remain separate checks. */
public final class FrozenDependencyIndexService {
    private final JdbcFrozenInputs inputs;private final JdbcFrozenDependencies index;
    public FrozenDependencyIndexService(JdbcFrozenInputs inputs,JdbcFrozenDependencies index){this.inputs=Objects.requireNonNull(inputs);this.index=Objects.requireNonNull(index);}
    public FrozenDependencyManifest.Manifest read(String owner,String inputHash){
        WireValues.id(owner,false);
        var input=CanonicalJson.parse(inputs.read(owner,inputHash));
        var manifest=FrozenDependencyManifest.read(input.get(FrozenDependencyManifest.FIELD));
        var parts=index.read(owner);
        if(parts.size()>10000||parts.size()!=manifest.dependencies().size())throw unavailable();
        var expected=new HashMap<String,Dependency>();manifest.dependencies().forEach(dep->expected.put(dep.id(),dep));
        for(int n=0;n<parts.size();n++){
            var part=parts.get(n);
            if(!owner.equals(part.owner())||!"input".equals(part.stamp())||part.number()!=n||owner.equals(part.dependency())||!JdbcFrozenInputs.stableId("dependency",owner+":"+part.dependency()).equals(part.id()))throw unavailable();
            var dep=expected.remove(part.dependency());
            if(dep==null||!dep.id().equals(part.upstreamId())||!"logical_task".equals(part.upstreamKind())||!dep.stage().name().toLowerCase(Locale.ROOT).equals(part.upstreamType()))throw unavailable();
            var node=CanonicalJson.parse(part.payload());var fields=Set.of("id","stage","state","completed_at");WireValues.object(node,fields,fields);
            if(!Arrays.equals(part.hash(),HexFormat.of().parseHex(CanonicalJson.hash(CanonicalJson.Domain.INPUT_PART,node))))throw unavailable();
            var actual=new Dependency(WireValues.id(node.get("id"),false),Stage.valueOf(WireValues.text(node.get("stage"))),WireValues.text(node.get("state")),node.get("completed_at").isNull()?null:Instant.parse(WireValues.text(node.get("completed_at"))));
            if(!dep.equals(actual))throw unavailable();
        }
        if(!expected.isEmpty())throw unavailable();return manifest;
    }
    private static IllegalStateException unavailable(){return new IllegalStateException("frozen dependency index unavailable");}
}
