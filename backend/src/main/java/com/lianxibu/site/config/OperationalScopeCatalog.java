package com.lianxibu.site.config;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.service.jobs.OperationalAdmissionService.Scopes;
import com.lianxibu.site.service.jobs.OperationalRuleEngine.Key;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
/** Host-owned resource binding; absence means unknown, never default healthy resources. */
public record OperationalScopeCatalog(Scopes scopes) {
    @Override public String toString(){return "OperationalScopeCatalog[redacted]";}
    public static OperationalScopeCatalog load(String location){
        if(location==null||location.isBlank())return new OperationalScopeCatalog(null);
        try{
            Path path=Path.of(location);if(!path.isAbsolute()||Files.isSymbolicLink(path)||!Files.isRegularFile(path)||Files.size(path)>65536)throw new IllegalArgumentException();
            var root=WireValues.object(CanonicalJson.parse(Files.readAllBytes(path)),Set.of("capacity","dispatch","sources"),Set.of("capacity","dispatch","sources"));
            var sourceMap=root.get("sources");if(!sourceMap.isObject()||sourceMap.size()>128)throw new IllegalArgumentException();
            var sources=new HashMap<String,List<Key>>();for(var field:sourceMap.properties())sources.put(field.getKey(),keys(field.getValue()));
            return new OperationalScopeCatalog(new Scopes(keys(root.get("capacity")),keys(root.get("dispatch")),sources));
        }catch(IOException|RuntimeException invalid){throw new IllegalStateException("operational resource binding unavailable");}
    }
    private static List<Key> keys(JsonNode value){
        if(value==null||!value.isArray()||value.size()>3)throw new IllegalArgumentException();
        var result=new ArrayList<Key>();for(var item:value){var fields=Set.of("rule_id","component","resource_key");WireValues.object(item,fields,fields);
            result.add(new Key(WireValues.text(item.get("rule_id")),WireValues.text(item.get("component")),WireValues.text(item.get("resource_key"))));}
        return List.copyOf(result);
    }
}
