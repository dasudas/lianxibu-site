package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.service.jobs.GenerationFlowService.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class FrozenDependencyManifestTest {
 final Dependency first=new Dependency("fictional-a",Stage.NEWS_DEDUP,"succeeded",Instant.parse("2026-01-01T20:00:00Z"));
 final Dependency second=new Dependency("fictional-b",Stage.NEWS_VALUE,"running",null);
 @Test void stableFullManifestKeepsUnavailableExpectedIdsAndOriginalStates(){
  var a=FrozenDependencyManifest.create(List.of(second,first),List.of("fictional-missing","fictional-a"));var b=FrozenDependencyManifest.create(List.of(first,second),List.of("fictional-a","fictional-missing"));assertArrayEquals(CanonicalJson.bytes(a),CanonicalJson.bytes(b));var read=FrozenDependencyManifest.read(a);assertEquals(List.of(first,second),read.dependencies());assertEquals(List.of("fictional-a","fictional-missing"),read.expectedIds());assertNull(read.dependencies().get(1).completedAt());assertThrows(UnsupportedOperationException.class,()->read.expectedIds().clear());
 }
 @Test void changedDependenciesChangeInputIdentityAndClosedSchemaRejectsExtraFields(){var a=(ObjectNode)FrozenDependencyManifest.create(List.of(first),List.of());var b=FrozenDependencyManifest.create(List.of(first,second),List.of());assertNotEquals(CanonicalJson.hash(CanonicalJson.Domain.INPUT,a),CanonicalJson.hash(CanonicalJson.Domain.INPUT,b));a.put("unknown",true);assertThrows(RuntimeException.class,()->FrozenDependencyManifest.read(a));assertThrows(RuntimeException.class,()->FrozenDependencyManifest.read(null));}
 @Test void duplicateAndUnboundedCollectionsCannotPretendComplete(){assertThrows(RuntimeException.class,()->FrozenDependencyManifest.create(List.of(first,first),List.of()));assertThrows(RuntimeException.class,()->FrozenDependencyManifest.create(List.of(first),List.of("fictional-a","fictional-a")));assertThrows(RuntimeException.class,()->FrozenDependencyManifest.create(Collections.nCopies(10001,first),List.of()));var invalid=new Dependency(first.id(),first.stage(),"",first.completedAt());assertThrows(RuntimeException.class,()->FrozenDependencyManifest.create(List.of(invalid),List.of()));}
}
