package org.example.dbinfo;

import spoon.Launcher;
import spoon.reflect.CtModel;

import java.nio.file.*;
import java.util.*;
import java.io.IOException;

/**
 * Database info orchestrator using Spoon (Java-only, non-Maven projects supported).
 * - Detects Java source roots (src/main/java, src/java) under the target repo
 * - Builds CtModel with Spoon Launcher in no-classpath mode (works for old Grails apps)
 * - Invokes DB extractors:
 *     - extractors.JpaEntityExtractor
 *     - extractors.JpaRelationshipExtractor
 *     - extractors.DbInteractionExtractor
 *
 * Example run (IDE Program arguments):
 *   --repo=/Users/jonathan.nervaez/Documents/AppModPractice/repo-to-refactor_test/jBilling --out=out/db-info --java=17
 */
public final class DbInfoRunner {

    public static void main(String[] args) throws Exception {
        Map<String, String> cli = parseArgs(args);

        Path repoPath = Paths.get(cli.getOrDefault("repo", "../jBilling")).toAbsolutePath().normalize();
        Path outDir   = Paths.get(cli.getOrDefault("out", "out/db-info")).toAbsolutePath().normalize();
        int  compliance = parseInt(cli.getOrDefault("java", "17"), 17);

        log("");
        log("=== DbInfoRunner (Spoon-only, no Maven) ===");
        log("repo = " + repoPath);
        log("out  = " + outDir);
        log("java = " + compliance);

        ensureDir(outDir);

        // 1) Discover Java source roots inside the target repo
        List<Path> sourceRoots = discoverJavaSourceRoots(repoPath);
        if (sourceRoots.isEmpty()) {
            log("ERROR: No Java source roots found (looked for src/main/java or src/java).");
            log("       Please verify the repo path or add Java sources.");
            return;
        }
        log("Source roots:");
        for (Path p : sourceRoots) log(" - " + p);

        // 2) Build model with Spoon Launcher in no-classpath mode
        Launcher launcher = new Launcher();
        launcher.getEnvironment().setComplianceLevel(compliance);
        launcher.getEnvironment().setNoClasspath(true); // works without resolving external deps
        launcher.getEnvironment().setIgnoreDuplicateDeclarations(true);

        for (Path src : sourceRoots) {
            launcher.addInputResource(src.toString());
        }

        log("Building CtModel...");
        launcher.buildModel();
        CtModel model = launcher.getModel();
        log("Types in model: " + model.getAllTypes().size());

        // 3) Run extractors (each writes its own JSON to outDir)
        log("Running extractors...");
        new org.example.dbinfo.extractors.JpaEntityExtractor().run(model, outDir);
        new org.example.dbinfo.extractors.JpaRelationshipExtractor().run(model, outDir);
        new org.example.dbinfo.extractors.DbInteractionExtractor().run(model, outDir);

        log("Done ✅  Expected files at: " + outDir);
        log(" - entities.json");
        log(" - relationships.json");
        log(" - db_interactions.json");
        log("");
    }

    // -------- source root discovery --------

    /** Finds src/main/java and src/java under the repo (depth-limited). */
    private static List<Path> discoverJavaSourceRoots(Path repoRoot) throws IOException {
        List<Path> roots = new ArrayList<>();
        // common direct candidates
        Path m = repoRoot.resolve("src/main/java");
        Path s = repoRoot.resolve("src/java");
        if (Files.isDirectory(m)) roots.add(m);
        if (Files.isDirectory(s)) roots.add(s);

        // if not found at root, scan shallowly for nested modules
        if (roots.isEmpty()) {
            try (var stream = Files.walk(repoRoot, 4)) {
                stream.filter(Files::isDirectory).forEach(p -> {
                    String norm = p.toString().replace('\\', '/');
                    if (norm.endsWith("/src/main/java") || norm.endsWith("/src/java")) {
                        roots.add(p);
                    }
                });
            }
        }
        // de-duplicate
        LinkedHashSet<Path> set = new LinkedHashSet<>(roots);
        return new ArrayList<>(set);
    }

    // -------- utilities --------

    private static void ensureDir(Path dir) throws Exception {
        if (Files.notExists(dir)) Files.createDirectories(dir);
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new LinkedHashMap<>();
        if (args == null) return m;
        for (String a : args) {
            if (a != null && a.startsWith("--") && a.contains("=")) {
                int i = a.indexOf('=');
                String k = a.substring(2, i).trim();
                String v = a.substring(i + 1).trim();
                if (!k.isEmpty()) m.put(k, v);
            }
        }
        return m;
    }

    private static void log(String s) {
        System.out.println("[DbInfoRunner] " + s);
    }
}
