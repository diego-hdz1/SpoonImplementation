package org.example.dbinfo.extractors;

import org.example.dbinfo.io.JsonUtil;

import spoon.reflect.CtModel;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtModifiable;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

/**
 * Extracts DB interactions (repositories, transactional sites, and API-level DB calls)
 * into db_interactions.json (Java-only, Spoon-based).
 */
public final class DbInteractionExtractor {

    public void run(CtModel model, Path outDir) throws Exception {
        Result result = extract(model);
        String json = JsonUtil.pretty(toJson(result));
        Path file = outDir.resolve("db_interactions.json");
        ensureDir(file.getParent());
        Files.writeString(file, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        log("db_interactions.json written: " + file.toAbsolutePath());
    }

    // ---------------- core extraction ----------------

    private Result extract(CtModel model) {
        Result r = new Result();

        // 1) Repositories: interfaces extending *Repository OR annotated @Repository
        for (CtType<?> t : model.getAllTypes()) {
            if (t.isInterface()) {
                boolean isRepo = t.getSuperInterfaces().stream()
                        .map(CtTypeReference::getQualifiedName)
                        .filter(Objects::nonNull)
                        .anyMatch(q -> q.endsWith("Repository")
                                || q.endsWith("JpaRepository")
                                || q.endsWith("CrudRepository")
                                || q.endsWith("PagingAndSortingRepository")
                                || q.contains("Repository"));
                if (isRepo || hasAnnoEnding(t, "Repository")) {
                    r.repositories.add(new RepoInfo(t.getQualifiedName(), "interface", superInterfacesOf(t)));
                }
            } else if (t instanceof CtClass<?>) {
                if (hasAnnoEnding(t, "Repository")) {
                    r.repositories.add(new RepoInfo(t.getQualifiedName(), "class", List.of()));
                }
            }
        }

        // 2) Transactional sites: @Transactional at class or method level
        for (CtType<?> t : model.getAllTypes()) {
            if (hasAnnoEnding(t, "Transactional")) {
                r.transactionalSites.add(t.getQualifiedName());
            }
            if (t instanceof CtClass<?>) {
                CtClass<?> c = (CtClass<?>) t;
                for (CtMethod<?> m : c.getMethods()) {
                    if (hasAnnoEnding(m, "Transactional")) {
                        r.transactionalSites.add(c.getQualifiedName() + "#" + m.getSimpleName());
                    }
                }
            }
        }

        // 3) Interactions by API: scan method call sites
        for (CtType<?> t : model.getAllTypes()) {
            if (!(t instanceof CtClass<?>)) continue;
            CtClass<?> c = (CtClass<?>) t;

            for (CtMethod<?> m : c.getMethods()) {
                if (m.getBody() == null) continue;

                // Use TypeFilter to get a typed list and avoid type mismatch errors
                for (CtInvocation<?> inv : m.getElements(new TypeFilter<>(CtInvocation.class))) {
                    CtExecutableReference<?> exec = inv.getExecutable();
                    if (exec == null) continue;

                    CtTypeReference<?> declType = exec.getDeclaringType();
                    String decl = declType != null ? declType.getQualifiedName() : null;
                    String methodName = exec.getSimpleName();

                    Kind kind = classifyKind(decl);
                    if (kind == null) {
                        // Heuristic: direct calls to Repository interfaces/classes
                        if (decl != null && decl.endsWith("Repository")) {
                            r.interactions.add(Interaction.repoCall(siteOf(c, m), decl, methodName));
                        }
                        continue;
                    }

                    String sql = firstStringLiteral(inv).orElse(null);

                    r.interactions.add(new Interaction(
                            siteOf(c, m),
                            kind.name(),
                            decl,
                            methodName,
                            decl,
                            sql,
                            null
                    ));
                }
            }
        }

        log("Repositories: " + r.repositories.size()
            + " | Transactional sites: " + r.transactionalSites.size()
            + " | Interactions: " + r.interactions.size());

        return r;
    }

    // ---------------- classification helpers ----------------

    private enum Kind { JPA, Hibernate, SpringJDBC, JDBC, RepoCall }

    private Kind classifyKind(String declaringType) {
        if (declaringType == null) return null;
        if (declaringType.startsWith("javax.persistence.")
                || declaringType.startsWith("jakarta.persistence.")) return Kind.JPA;
        if (declaringType.startsWith("org.hibernate.")
                || declaringType.startsWith("org.hibernate.query.")) return Kind.Hibernate;
        if (declaringType.startsWith("org.springframework.jdbc.core.")) return Kind.SpringJDBC;
        if (declaringType.startsWith("java.sql.")) return Kind.JDBC;
        return null;
    }

    // ---------------- small utilities ----------------

    private static boolean hasAnnoEnding(CtModifiable el, String suffix) {
        return el.getAnnotations().stream().anyMatch(a -> {
            var at = a.getAnnotationType();
            String q = at != null ? at.getQualifiedName() : null;
            return q != null && q.endsWith(suffix);
        });
    }

    private static List<String> superInterfacesOf(CtType<?> t) {
        List<String> list = new ArrayList<>();
        for (CtTypeReference<?> r : t.getSuperInterfaces()) {
            String q = r.getQualifiedName();
            if (q != null) list.add(q);
        }
        return list;
    }

    private static String siteOf(CtClass<?> c, CtMethod<?> m) {
        return c.getQualifiedName() + "#" + m.getSimpleName();
    }

    private static Optional<String> firstStringLiteral(CtInvocation<?> inv) {
        List<CtExpression<?>> args = inv.getArguments();
        if (args == null || args.isEmpty()) return Optional.empty();
        CtExpression<?> first = args.get(0);
        if (first == null) return Optional.empty();
        String s = first.toString();
        if (s == null) return Optional.empty();
        s = s.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            return Optional.of(s.substring(1, s.length() - 1));
        }
        return Optional.empty();
    }

    private static void ensureDir(Path p) throws Exception {
        if (p != null && Files.notExists(p)) Files.createDirectories(p);
    }

    private static void log(String s) {
        System.out.println("[DbInteractionExtractor] " + s);
    }

    // ---------------- DTOs ----------------

    static final class RepoInfo {
        String name;                 // FQN
        String kind;                 // "interface" | "class"
        List<String> extendsTypes;   // FQNs of super interfaces

        RepoInfo(String name, String kind, List<String> extendsTypes) {
            this.name = name;
            this.kind = kind;
            this.extendsTypes = extendsTypes == null ? List.of() : extendsTypes;
        }
    }

    static final class Interaction {
        String site;           // FQN#method
        String kind;           // JPA | Hibernate | SpringJDBC | JDBC | RepoCall
        String api;            // Declaring type (group)
        String method;         // Invoked method name
        String declaringType;  // Declaring type FQN
        String sqlLiteral;     // If first arg is a string literal
        String notes;          // Optional

        Interaction(String site, String kind, String api, String method, String declaringType, String sqlLiteral, String notes) {
            this.site = site;
            this.kind = kind;
            this.api = api;
            this.method = method;
            this.declaringType = declaringType;
            this.sqlLiteral = sqlLiteral;
            this.notes = notes;
        }

        static Interaction repoCall(String site, String repoType, String method) {
            return new Interaction(site, Kind.RepoCall.name(), repoType, method, repoType, null, null);
        }
    }

    static final class Result {
        List<RepoInfo> repositories = new ArrayList<>();
        Set<String> transactionalSites = new TreeSet<>();
        List<Interaction> interactions = new ArrayList<>();
    }

    // ---------------- JSON (manual, no external deps) ----------------

    private String toJson(Result r) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");

        // repositories
        sb.append("\"repositories\":[");
        for (int i = 0; i < r.repositories.size(); i++) {
            RepoInfo repo = r.repositories.get(i);
            if (i > 0) sb.append(",");
            sb.append("{");
            kv(sb, "name", repo.name).append(",");
            kv(sb, "kind", repo.kind).append(",");
            sb.append("\"extends\":[");
            for (int j = 0; j < repo.extendsTypes.size(); j++) {
                if (j > 0) sb.append(",");
                sb.append(string(repo.extendsTypes.get(j)));
            }
            sb.append("]");
            sb.append("}");
        }
        sb.append("],");

        // transactionalSites
        sb.append("\"transactionalSites\":[");
        int c = 0;
        for (String s : r.transactionalSites) {
            if (c++ > 0) sb.append(",");
            sb.append(string(s));
        }
        sb.append("],");

        // interactions
        sb.append("\"interactions\":[");
        for (int i = 0; i < r.interactions.size(); i++) {
            Interaction it = r.interactions.get(i);
            if (i > 0) sb.append(",");
            sb.append("{");
            kv(sb, "site", it.site).append(",");
            kv(sb, "kind", it.kind).append(",");
            kv(sb, "api", it.api).append(",");
            kv(sb, "method", it.method).append(",");
            kv(sb, "declaringType", it.declaringType).append(",");
            kv(sb, "sqlLiteral", it.sqlLiteral).append(",");
            kv(sb, "notes", it.notes);
            sb.append("}");
        }
        sb.append("]");

        sb.append("}");
        return sb.toString();
    }

    private static StringBuilder kv(StringBuilder sb, String k, String v) {
        sb.append("\"").append(escape(k)).append("\":").append(string(v));
        return sb;
    }

    private static String string(String s) {
        if (s == null) return "null";
        return "\"" + escape(s) + "\"";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

