package com.lianxibu.site.service.jobs;
import java.util.List;
/** Public metadata for the configured business graph; never a dispatch or publication permit. */
public final class RuntimeCapabilities {
    public static final String FORMAL_BUSINESS = "formal-business-v1";
    public List<String> supportedFeatures() { return List.of(FORMAL_BUSINESS); }
}
