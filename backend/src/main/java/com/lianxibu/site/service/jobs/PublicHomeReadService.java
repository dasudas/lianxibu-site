package com.lianxibu.site.service.jobs;

import com.lianxibu.site.service.content.ContentService;
import com.lianxibu.site.service.content.ContentService.ContentView;
import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.learning.LearningService;
import com.lianxibu.site.service.learning.LearningService.PackageSummary;
import com.lianxibu.site.service.settings.SettingsReadService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Home aggregation over service boundaries, with current eligibility checked on every call. */
public final class PublicHomeReadService implements PublicReadService {
    @FunctionalInterface public interface SnapshotReports { List<ContentView> read(); }
    private final ContentService contents;
    private final LearningService learning;
    private final SettingsReadService settings;
    private final TransactionTemplate reads;
    private final SnapshotReports nativeReports;

    public PublicHomeReadService(ContentService contents, LearningService learning,
                                 SettingsReadService settings,
                                 PlatformTransactionManager tx) {
        this(contents,learning,settings,tx,null);
    }

    public PublicHomeReadService(ContentService contents,LearningService learning,SettingsReadService settings,
                                 PlatformTransactionManager tx,SnapshotReports nativeReports) {
        if (contents == null || learning == null || settings == null || tx == null) {
            throw new IllegalArgumentException("home read dependencies required");
        }
        this.contents = contents;
        this.learning = learning;
        this.settings = settings;
        this.nativeReports = nativeReports;
        reads = new TransactionTemplate(tx);
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override public HomeSnapshot home() {
        return reads.execute(status -> {
            List<ContentView> reportItems = reports();
            List<PackageSummary> packageItems = new ArrayList<>(packages());
            String selected = settings.resolvePublishedPackage(null).orElse(null);
            boolean listed = false;
            for (PackageSummary item : packageItems) {
                if (item.id().equals(selected)) { listed = true; break; }
            }
            if (selected != null && !listed) {
                PackageSummary value = currentPackage(selected);
                if (value == null) selected = null;
                else {
                    if (packageItems.size() == 50) packageItems.removeLast();
                    packageItems.addFirst(value);
                }
            }
            return new HomeSnapshot(reportItems, List.copyOf(packageItems), selected);
        });
    }

    private List<ContentView> reports() {
        if(nativeReports!=null)return nativeReportPage();
        var page = contents.reports(null, 1, 50);
        if (page == null || page.items() == null) throw new IllegalStateException("content page missing");
        List<ContentView> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ContentView report : page.items()) {
            if (report == null || !"report".equals(report.kind()) || report.id() == null
                    || !seen.add(report.id()) || !"published".equals(report.publicationState())
                    || report.reviewRequired() || report.blockingReasons() == null
                    || !report.blockingReasons().isEmpty()) continue;
            ContentView current = currentReport(report.id());
            if (current != null && current.revision() == report.revision()
                    && current.bodyRevision() == report.bodyRevision()
                    && current.publishedAt() != null
                    && current.publishedAt().equals(report.publishedAt())) {
                result.add(current);
            }
            if (result.size() == 50) break;
        }
        return List.copyOf(result);
    }

    /** The native provider performs complete current qualification in this same snapshot on every call. */
    private List<ContentView> nativeReportPage() {
        var page=nativeReports.read();
        if(page==null||page.size()>50)throw new IllegalStateException("native report page unavailable");
        Set<String> seen=new HashSet<>();
        for(var report:page){
            if(report==null||!"report".equals(report.kind())||report.id()==null||report.id().isEmpty()||!seen.add(report.id())
                    ||report.revision()<1||report.bodyRevision()<1||!"published".equals(report.publicationState())||report.publishedAt()==null
                    ||report.reviewRequired()||report.blockingReasons()==null||!report.blockingReasons().isEmpty()
                    ||report.inputSnapshotId()==null||report.generation()==null||report.generation()<1||report.inputCutoffAt()==null
                    ||report.coverage()==null||!(report.body() instanceof ContentService.ReportBody)||report.citations()==null)
                throw new IllegalStateException("native report page unavailable");
        }
        return List.copyOf(page);
    }

    private List<PackageSummary> packages() {
        var page = learning.packages(1, 50);
        if (page == null || page.items() == null) throw new IllegalStateException("learning page missing");
        List<PackageSummary> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (PackageSummary candidate : page.items()) {
            if (candidate == null || candidate.id() == null || !seen.add(candidate.id())) continue;
            PackageSummary current = currentPackage(candidate.id());
            if (current != null && current.revision() == candidate.revision()) result.add(current);
            if (result.size() == 50) break;
        }
        return List.copyOf(result);
    }

    private PackageSummary currentPackage(String id) {
        try {
            PackageSummary value = learning.packageSummary(id);
            return value != null && id.equals(value.id()) && "published".equals(value.state())
                    ? value : null;
        } catch (IdentityService.Rejected missing) {
            if (missing.refusal() == IdentityService.Refusal.NOT_FOUND) return null;
            throw missing;
        }
    }

    private ContentView currentReport(String id) {
        try {
            ContentView value = contents.report(id);
            return value != null && id.equals(value.id()) && "report".equals(value.kind())
                    && "published".equals(value.publicationState()) && !value.reviewRequired()
                    && value.blockingReasons() != null && value.blockingReasons().isEmpty()
                    ? value : null;
        } catch (IdentityService.Rejected missing) {
            if (missing.refusal() == IdentityService.Refusal.NOT_FOUND) return null;
            throw missing;
        }
    }
}
