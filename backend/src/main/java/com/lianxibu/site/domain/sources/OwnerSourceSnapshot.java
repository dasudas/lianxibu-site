package com.lianxibu.site.domain.sources;
import java.time.Instant;
/** Current source columns. Unobserved database revisions remain null. */
public record OwnerSourceSnapshot(String id,String sourceKind,String name,Long revision,Long configRevision,String activationStage,String category,String draftConfigJson,String effectiveConfigJson,Instant configValidUntil){
 @FunctionalInterface public interface Store {OwnerSourceSnapshot snapshot(String sourceId);}
 @Override public String toString(){return "OwnerSourceSnapshot[id="+id+"]";}
}
