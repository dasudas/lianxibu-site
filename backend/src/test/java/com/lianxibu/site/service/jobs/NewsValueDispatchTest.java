package com.lianxibu.site.service.jobs;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class NewsValueDispatchTest extends SourceStageBirthConfigurationTest {
    boolean legacy;
    @Override boolean materialView(){return !legacy;}
    @Test void actualProductionConfigurationRejectsLegacyValueWithoutMaterialProof(){
        legacy=true;archive(1,0);var current=archive(2,5);var prepared=prepare(current);int count=f.f.count("1=1");
        assertThrows(RuntimeException.class,()->f.tx(()->dispatch().ready(prepared.ownerId())));assertEquals(count,f.f.count("1=1"));assertEquals(0,f.f.jdbc.queryForObject("SELECT SUM(call_count) FROM jobs",Integer.class));
    }
}
