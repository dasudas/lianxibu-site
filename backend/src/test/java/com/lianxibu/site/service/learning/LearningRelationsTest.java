package com.lianxibu.site.service.learning;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.repository.learning.LearningRelations;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class LearningRelationsTest {
    @Test void selectionFreezesOriginalOrderAndRejectsUnboundedOrAmbiguousIds() {
        var ids=new ArrayList<>(List.of("fictional-second","fictional-first"));
        var selection=new LearningRelations.Selection("fictional-package",ids);
        ids.clear();
        assertEquals(List.of("fictional-second","fictional-first"),selection.itemIds());
        assertThrows(UnsupportedOperationException.class,()->selection.itemIds().add("fictional-third"));
        assertEquals(50,new LearningRelations.Selection("fictional-package",
                IntStream.range(0,50).mapToObj(i->"fictional-"+i).toList()).itemIds().size());
        assertThrows(IllegalArgumentException.class,()->new LearningRelations.Selection("fictional-package",
                IntStream.range(0,51).mapToObj(i->"fictional-"+i).toList()));
        assertThrows(IllegalArgumentException.class,()->new LearningRelations.Selection("fictional-package",List.of("fictional-first","fictional-first")));
        assertThrows(IllegalArgumentException.class,()->new LearningRelations.Selection("fictional-package",List.of()));
        assertEquals(List.of("fictional space", "虚构标识"),new LearningRelations.Selection("fictional-package",List.of("fictional space", "虚构标识")).itemIds());
        assertThrows(IllegalArgumentException.class,()->new LearningRelations.Selection("fictional-package",List.of("bad"+(char)1+"identifier")));
    }
    @Test void nodeFrameKeepsRawOrderAndMissingResourceFactsWithoutLaterMutation() {
        var modules=new ArrayList<>(List.of("fictional-module"));
        var dependencies=new ArrayList<>(List.of("fictional-second","fictional-first"));
        var resources=new ArrayList<>(List.of(new LearningRelations.ResourceData("Fictional","{\"url\":\"https://example.invalid/fictional\"}"),
                new LearningRelations.ResourceData(null,null)));
        var frame=new LearningRelations.NodeFrame(modules,dependencies,resources);
        modules.clear();dependencies.clear();resources.clear();
        assertEquals(List.of("fictional-module"),frame.moduleIds());
        assertEquals(List.of("fictional-second","fictional-first"),frame.dependencyIds());
        assertEquals(2,frame.resources().size());assertNull(frame.resources().getLast().bodyJson());
        assertThrows(UnsupportedOperationException.class,()->frame.resources().clear());
        assertFalse(frame.resources().getFirst().toString().contains("example.invalid"));
        assertEquals(List.of(),new LearningRelations.NodeFrame(List.of(),List.of(),List.of()).moduleIds());
    }
    @Test void routeFrameKeepsExactModuleOrderAndSafeCount() {
        var modules=new ArrayList<>(List.of("fictional-second","fictional-first"));
        var frame=new LearningRelations.RouteFrame(modules,0);modules.clear();
        assertEquals(List.of("fictional-second","fictional-first"),frame.moduleIds());
        assertEquals(0,frame.eligibleNodeCount());
        assertEquals(CanonicalJson.MAX_SAFE_INTEGER,new LearningRelations.RouteFrame(List.of(),CanonicalJson.MAX_SAFE_INTEGER).eligibleNodeCount());
        assertThrows(IllegalArgumentException.class,()->new LearningRelations.RouteFrame(List.of(),-1));
        assertThrows(IllegalArgumentException.class,()->new LearningRelations.RouteFrame(List.of(),CanonicalJson.MAX_SAFE_INTEGER+1));
    }
}
