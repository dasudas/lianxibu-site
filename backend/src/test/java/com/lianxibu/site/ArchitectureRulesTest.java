package com.lianxibu.site;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ArchitectureRulesTest {
    private static final Path ROOT = Path.of("src/main/java/com/lianxibu/site");
    private static final Set<String> LAYERS = Set.of("controller", "service", "repository", "domain");
    private static final Set<String> MODULES = Set.of("identity", "sources", "articles", "content", "learning", "records", "jobs", "settings");
    private static final Pattern BUSINESS_IMPORT = Pattern.compile(
            "^import com\\.lianxibu\\.site\\.(controller|service|repository|domain)\\.([a-z]+)\\.", Pattern.MULTILINE);

    @Test
    void productionSourcesRespectLayerAndModuleBoundaries() throws IOException {
        try (var files = Files.walk(ROOT)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                List<String> errors = violations(file, Files.readString(file));
                assertTrue(errors.isEmpty(), () -> "Architecture violations in " + file + ": " + errors);
            }
        }
    }

    @Test
    void checkerRejectsCrossModuleRepositoryAndControllerToRepositoryImports() {
        assertFalse(violations(Path.of("src/main/java/com/lianxibu/site/service/jobs/Bad.java"),
                "import com.lianxibu.site.repository.articles.ArticleRepository;").isEmpty());
        assertFalse(violations(Path.of("src/main/java/com/lianxibu/site/controller/jobs/Bad.java"),
                "import com.lianxibu.site.repository.jobs.JobRepository;").isEmpty());
    }

    private static List<String> violations(Path file, String source) {
        String relative = ROOT.relativize(file).toString().replace('\\', '/');
        String[] pathParts = relative.split("/");
        if (pathParts.length < 3 || !LAYERS.contains(pathParts[0]) || !MODULES.contains(pathParts[1])) {
            return List.of();
        }
        String fromLayer = pathParts[0];
        String fromModule = pathParts[1];
        ArrayList<String> errors = new ArrayList<>();
        Matcher imports = BUSINESS_IMPORT.matcher(source);
        while (imports.find()) {
            String toLayer = imports.group(1);
            String toModule = imports.group(2);
            if (fromLayer.equals("domain")
                    || (fromLayer.equals("controller") && !toLayer.equals("service") && !toLayer.equals("domain"))
                    || (fromLayer.equals("repository") && !toLayer.equals("domain"))
                    || (fromLayer.equals("service") && toLayer.equals("controller"))) {
                errors.add("Illegal layer import: " + imports.group());
            }
            if (toLayer.equals("repository") && !fromModule.equals(toModule)) {
                errors.add("Cross-module repository import: " + imports.group());
            }
        }
        return errors;
    }
}
