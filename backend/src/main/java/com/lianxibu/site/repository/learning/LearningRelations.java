package com.lianxibu.site.repository.learning;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Batched original relationships; a frame does not authorize publication or a read snapshot. */
public interface LearningRelations {
    record Selection(String packageId, List<String> itemIds) {
        public Selection {
            if (itemIds==null || itemIds.isEmpty() || itemIds.size()>50
                    || new HashSet<>(itemIds).size()!=itemIds.size())
                throw new IllegalArgumentException("invalid learning relation selection");
            WireValues.id(packageId,false);
            itemIds.forEach(id->WireValues.id(id,false));
            itemIds=List.copyOf(itemIds);
        }
        @Override public String toString() { return "LearningRelationSelection[redacted]"; }
    }
    record ResourceData(String title, String bodyJson) {
        @Override public String toString() { return "LearningResourceData[redacted]"; }
    }
    record NodeFrame(List<String> moduleIds, List<String> dependencyIds, List<ResourceData> resources) {
        public NodeFrame {
            moduleIds=List.copyOf(moduleIds);
            dependencyIds=List.copyOf(dependencyIds);
            resources=List.copyOf(resources);
        }
    }
    record RouteFrame(List<String> moduleIds, long eligibleNodeCount) {
        public RouteFrame {
            if (eligibleNodeCount<0 || eligibleNodeCount>CanonicalJson.MAX_SAFE_INTEGER)
                throw new IllegalArgumentException("invalid learning eligible count");
            moduleIds=List.copyOf(moduleIds);
        }
    }
    Map<String, NodeFrame> nodes(Selection selection);
    Map<String, RouteFrame> routes(Selection selection);
}
