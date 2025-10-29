package org.example.dbinfo.extractors;

import org.example.dbinfo.io.JsonUtil;

import spoon.reflect.CtModel;
import spoon.reflect.code.CtExpression;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtModifiable;
import spoon.reflect.reference.CtTypeReference;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

/**
 * Extracts JPA entities and basic field mapping info into entities.json.
 *
 * Output (stable contract):
 * {
 *   "entities": [
 *     {
 *       "name": "com.example.Invoice",
 *       "kind": "Entity",                // Entity | Embeddable | MappedSuperclass
 *       "table": "invoice",
 *       "idField": "id",
 *       "fields": [
 *         {"name":"amount","javaType":"java.math.BigDecimal","column":"amount","nullable":false,"length":null,"unique":null}
 *       ]
 *     }
 *   ]
 * }
 */
public final class JpaEntityExtractor {

    public void run(CtModel model, Path outDir) throws Exception {
        List<EntityInfo> entities = extract(model);
        String json = JsonUtil.pretty(toJson(entities));
        Path file = outDir.resolve("entities.json");
        ensureDir(file.getParent());
        Files.writeString(file, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        log("entities.json written: " + file.toAbsolutePath());
    }

    // ---------------- core extraction ----------------

    private List<EntityInfo> extract(CtModel model) {
        List<EntityInfo> out = new ArrayList<>();

        for (var t : model.getAllTypes()) {
            if (!(t instanceof CtClass<?>)) continue;
            CtClass<?> clazz = (CtClass<?>) t;

            Kind kind = detectKind(clazz);
            if (kind == null) continue;

            EntityInfo e = new EntityInfo();
            e.name  = clazz.getQualifiedName();
            e.kind  = kind;
            e.table = readAnnoStringValue(clazz, "Table", "name").orElse(null);

            // Primary key (Id / EmbeddedId) - basic heuristic
            for (CtField<?> f : clazz.getFields()) {
                if (hasAnnoEnding(f, "Id") || hasAnnoEnding(f, "EmbeddedId")) {
                    e.idField = f.getSimpleName();
                    break;
                }
            }

            // Fields with @Column metadata if present
            for (CtField<?> f : clazz.getFields()) {
                FieldInfo fi = new FieldInfo();
                fi.name     = f.getSimpleName();
                fi.javaType = safeTypeName(f.getType());
                fi.column   = readAnnoStringValue(f, "Column", "name").orElse(null);
                fi.nullable = readAnnoBoolValue(f, "Column", "nullable").orElse(null);
                fi.length   = readAnnoIntValue(f, "Column", "length").orElse(null);
                fi.unique   = readAnnoBoolValue(f, "Column", "unique").orElse(null);
                e.fields.add(fi);
            }

            out.add(e);
        }

        log("JPA entities detected: " + out.size());
        return out;
    }

    // ---------------- helpers: annotation & types ----------------

    private enum Kind { Entity, Embeddable, MappedSuperclass }

    private Kind detectKind(CtClass<?> clazz) {
        if (hasAnnoEnding(clazz, "Entity")) return Kind.Entity;
        if (hasAnnoEnding(clazz, "Embeddable")) return Kind.Embeddable;
        if (hasAnnoEnding(clazz, "MappedSuperclass")) return Kind.MappedSuperclass;
        return null;
    }

    private static boolean hasAnnoEnding(CtModifiable el, String suffix) {
        return el.getAnnotations().stream().anyMatch(a -> {
            var at = a.getAnnotationType();
            String q = at != null ? at.getQualifiedName() : null;
            return q != null && q.endsWith(suffix);
        });
    }

    private static Optional<String> readAnnoStringValue(CtModifiable el, String annoSuffix, String key) {
        return el.getAnnotations().stream()
                .filter(a -> {
                    var at = a.getAnnotationType();
                    String q = at != null ? at.getQualifiedName() : null;
                    return q != null && q.endsWith(annoSuffix);
                })
                .map(a -> a.getValues().get(key))
                .filter(Objects::nonNull)
                .map(JpaEntityExtractor::literalString)
                .filter(Objects::nonNull)
                .findFirst();
    }

    private static Optional<Boolean> readAnnoBoolValue(CtModifiable el, String annoSuffix, String key) {
        return el.getAnnotations().stream()
                .filter(a -> {
                    var at = a.getAnnotationType();
                    String q = at != null ? at.getQualifiedName() : null;
                    return q != null && q.endsWith(annoSuffix);
                })
                .map(a -> a.getValues().get(key))
                .filter(Objects::nonNull)
                .map(expr -> {
                    String s = expr.toString();
                    if ("true".equalsIgnoreCase(s)) return Boolean.TRUE;
                    if ("false".equalsIgnoreCase(s)) return Boolean.FALSE;
                    return null;
                })
                .filter(Objects::nonNull)
                .findFirst();
    }

    private static Optional<Integer> readAnnoIntValue(CtModifiable el, String annoSuffix, String key) {
        return el.getAnnotations().stream()
                .filter(a -> {
                    var at = a.getAnnotationType();
                    String q = at != null ? at.getQualifiedName() : null;
                    return q != null && q.endsWith(annoSuffix);
                })
                .map(a -> a.getValues().get(key))
                .filter(Objects::nonNull)
                .map(Object::toString)
                .map(String::trim)
                .map(v -> {
                    try { return Integer.parseInt(v); } catch (Exception ignore) { return null; }
                })
                .filter(Objects::nonNull)
                .findFirst();
    }

    private static String safeTypeName(CtTypeReference<?> ref) {
        if (ref == null) return null;
        String q = ref.getQualifiedName();
        return q != null ? q : ref.getSimpleName();
    }

    private static String literalString(CtExpression<?> expr) {
        if (expr == null) return null;
        String s = expr.toString();
        if (s == null) return null;
        s = s.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    // ---------------- DTOs ----------------

    static final class EntityInfo {
        String name;                 // Fully qualified class name
        Kind   kind;                 // Entity | Embeddable | MappedSuperclass
        String table;                // Table name if @Table(name=...)
        String idField;              // Field name annotated with @Id or @EmbeddedId
        List<FieldInfo> fields = new ArrayList<>();
    }

    static final class FieldInfo {
        String  name;                // Field name
        String  javaType;            // Fully qualified type
        String  column;              // @Column(name=...)
        Boolean nullable;            // @Column(nullable=...)
        Integer length;              // @Column(length=...)
        Boolean unique;              // @Column(unique=...)
    }

    // ---------------- JSON (manual, no external deps) ----------------

    private String toJson(List<EntityInfo> entities) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"entities\":[");
        for (int i = 0; i < entities.size(); i++) {
            EntityInfo e = entities.get(i);
            if (i > 0) sb.append(",");
            sb.append("{");
            kv(sb, "name", e.name).append(",");
            kv(sb, "kind", e.kind != null ? e.kind.name() : null).append(",");
            kv(sb, "table", e.table).append(",");
            kv(sb, "idField", e.idField).append(",");
            // fields
            sb.append("\"fields\":[");
            for (int j = 0; j < e.fields.size(); j++) {
                FieldInfo f = e.fields.get(j);
                if (j > 0) sb.append(",");
                sb.append("{");
                kv(sb, "name", f.name).append(",");
                kv(sb, "javaType", f.javaType).append(",");
                kv(sb, "column", f.column).append(",");
                kv(sb, "nullable", f.nullable).append(",");
                kv(sb, "length", f.length).append(",");
                kv(sb, "unique", f.unique);
                sb.append("}");
            }
            sb.append("]}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static StringBuilder kv(StringBuilder sb, String k, String v) {
        sb.append("\"").append(escape(k)).append("\":").append(string(v));
        return sb;
    }

    private static StringBuilder kv(StringBuilder sb, String k, Boolean v) {
        sb.append("\"").append(escape(k)).append("\":").append(v == null ? "null" : v.toString());
        return sb;
    }

    private static StringBuilder kv(StringBuilder sb, String k, Integer v) {
        sb.append("\"").append(escape(k)).append("\":").append(v == null ? "null" : v.toString());
        return sb;
    }

    private static String string(String s) {
        if (s == null) return "null";
        return "\"" + escape(s) + "\"";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ---------------- fs & log ----------------

    private static void ensureDir(Path p) throws Exception {
        if (p != null && Files.notExists(p)) Files.createDirectories(p);
    }

    private static void log(String s) {
        System.out.println("[JpaEntityExtractor] " + s);
    }
}
