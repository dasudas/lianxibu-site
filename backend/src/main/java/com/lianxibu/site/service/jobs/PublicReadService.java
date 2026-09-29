package com.lianxibu.site.service.jobs;

import com.lianxibu.site.service.content.ContentService.ContentView;
import com.lianxibu.site.service.learning.LearningService.PackageSummary;
import java.util.List;

/** Public homepage projection. The implementation must recheck visibility on every read. */
public interface PublicReadService {
    record HomeSnapshot(List<ContentView> reports, List<PackageSummary> learningPackages,
                        String defaultPackageId) { }

    HomeSnapshot home();
}
