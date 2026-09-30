package com.lianxibu.site.config;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class OperationalScopeCatalogTest {
    @TempDir Path directory;
    static String key(String rule,String component){return "{\"rule_id\":\""+rule+"\",\"component\":\""+component+"\",\"resource_key\":\"fictional-resource\"}";}
    static String scopes(){return "{\"capacity\":["+key("OPS-04","database")+","+key("OPS-10","monitor")+"],\"dispatch\":["+key("OPS-03","worker")+","+key("OPS-06","java")+","+key("OPS-08","provider")+"],\"sources\":{\"fictional-source\":["+key("OPS-07","source")+"]}}";}
    @Test void controlledFileIsStrictBoundedAndMissingBindingNeverAllowsNewInput()throws Exception{
        assertNull(OperationalScopeCatalog.load("").scopes());assertFalse(new OperationalInputGate(null).newRunAllowed("fictional-source"));assertFalse(new OperationalInputGate(null).uploadAllowed());
        Path file=directory.resolve("scopes.json");Files.writeString(file,scopes());var loaded=OperationalScopeCatalog.load(file.toString());assertEquals(2,loaded.scopes().capacity().size());assertEquals(1,loaded.scopes().sources().size());assertFalse(loaded.toString().contains("fictional-resource"));
        Files.writeString(file,scopes().replace("\"dispatch\":[","\"unknown\":true,\"dispatch\":["));assertThrows(IllegalStateException.class,()->OperationalScopeCatalog.load(file.toString()));
        Files.writeString(file,scopes().replace("OPS-10","OPS-04"));assertThrows(IllegalStateException.class,()->OperationalScopeCatalog.load(file.toString()));
        Files.writeString(file,"x".repeat(65537));assertThrows(IllegalStateException.class,()->OperationalScopeCatalog.load(file.toString()));
        assertThrows(IllegalStateException.class,()->OperationalScopeCatalog.load("relative.json"));
    }
}
