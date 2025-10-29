package org.example.dbinfo.extractors;

import org.example.dbinfo.io.JsonUtil;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtExpression;
import spoon.reflect.declaration.*;
import spoon.reflect.reference.CtTypeReference;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

/**
 * Extracts JPA entities into:
 *  - entities.json (full, with scalar fields)
 *  - entities_min.json (minimal, without fields)
 *
 * Features:
 *  - Supports FIELD and PROPERTY access (annotations on fields or getters).
 *  - Skips relations (@OneTo*, @ManyTo*) and noise (static/final/transient, @Transient, loggers, serialVersionUID, ALL_CAPS consts).
 *  - Optional filter to exclude classes whose simple name ends with "DTO".
 */
public final class JpaEntityExtractor {

    /** Toggle to exclude classes ending with "DTO" from outputs. */
    private static final boolean SKIP_DTO_BY_SUFFIX = true;

    public void run(CtModel model, Path outDir) throws Exception {
        List<Map<String, Object>> full = extract(model);
        // Build minimal view (no fields)
        List<Map<String, Object>> minimal = buildMinimal(full);

        // Write full
        String jsonFull = JsonUtil.pretty(toJson(full));
        Path fileFull = outDir.resolve("entities.json");
        ensureDir(fileFull.getParent());
        Files.writeString(fileFull, jsonFull, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        // Write minimal
        String jsonMin = JsonUtil.pretty(toJson(minimal));
        Path fileMin = outDir.resolve("entities_min.json");
        Files.writeString(fileMin, jsonMin, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        log("entities.json written: " + fileFull.toAbsolutePath());
        log("entities_min.json written: " + fileMin.toAbsolutePath());
    }

    // ---------------- core extraction ----------------

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extract(CtModel model) {
        List<Map<String, Object>> out = new ArrayList<>();

        for (CtType<?> t : model.getAllTypes()) {
            if (!(t instanceof CtClass<?>)) continue;
            CtClass<?> clazz = (CtClass<?>) t;

            String kind = detectKind(clazz);
            if (kind == null) continue; // only process JPA artifacts

            // Optional filter: skip DTO-suffixed classes
            String simpleName = clazz.getSimpleName();
            if (SKIP_DTO_BY_SUFFIX && simpleName != null && simpleName.endsWith("DTO")) {
                continue;
            }

            // Determine access mode: PROPERTY (annotations on getters) vs FIELD (annotations on fields)
            AccessMode access = detectAccessMode(clazz);

            Map<String, Object> e = new LinkedHashMap<>();
            e.put("name", clazz.getQualifiedName());
            e.put("kind", kind);
            e.put("table", readAnnoStringValue(clazz, "Table", "name").orElse(null));
            e.put("idField", null);
            e.put("fields", new ArrayList<Map<String, Object>>());

            if (access == AccessMode.PROPERTY) {
                // ID via getter
                clazz.getMethods().stream()
                        .filter(this::isGetter)
                        .filter(m -> hasAnnoEnding(m, "Id") || hasAnnoEnding(m, "EmbeddedId"))
                        .findFirst()
                        .ifPresent(m -> e.put("idField", propNameFromGetter(m.getSimpleName())));

                // scalar fields via getters
                for (CtMethod<?> m : clazz.getMethods()) {
                    if (!isGetter(m)) continue;
                    String prop = propNameFromGetter(m.getSimpleName());

                    // skip noise and non-scalar
                    if (skipByName(prop)) continue;
                    if (hasAnnoEnding(m, "Transient")) continue;
                    if (isRelationMethod(m)) continue;
                    if (isLoggerType(m.getType())) continue;

                    Map<String, Object> fi = new LinkedHashMap<>();
                    fi.put("name", prop);
                    fi.put("javaType", safeTypeName(m.getType()));
                    fi.put("column", readAnnoStringValue(m, "Column", "name").orElse(null));
                    fi.put("nullable", readAnnoBoolValue(m, "Column", "nullable").orElse(null));
                    fi.put("length", readAnnoIntValue(m, "Column", "length").orElse(null));
                    fi.put("unique", readAnnoBoolValue(m, "Column", "unique").orElse(null));
                    ((List<Map<String, Object>>) e.get("fields")).add(fi);
                }

            } else { // FIELD access (default)
                // ID via field
                clazz.getFields().stream()
                        .filter(f -> hasAnnoEnding(f, "Id") || hasAnnoEnding(f, "EmbeddedId"))
                        .findFirst()
                        .ifPresent(f -> e.put("idField", f.getSimpleName()));

                // scalar fields via fields
                for (CtField<?> f : clazz.getFields()) {
                    // skip noise
                    if (skipByName(f.getSimpleName())) continue;
                    Set<ModifierKind> mods = f.getModifiers();
                    if (mods.contains(ModifierKind.STATIC) || mods.contains(ModifierKind.TRANSIENT)) continue;
                    if (hasAnnoEnding(f, "Transient")) continue;
                    if (isRelationField(f)) continue;
                    if (isLoggerType(f.getType())) continue;

                    Map<String, Object> fi = new LinkedHashMap<>();
                    fi.put("name", f.getSimpleName());
                    fi.put("javaType", safeTypeName(f.getType()));
                    fi.put("column", readAnnoStringValue(f, "Column", "name").orElse(null));
                    fi.put("nullable", readAnnoBoolValue(f, "Column", "nullable").orElse(null));
                    fi.put("length", readAnnoIntValue(f, "Column", "length").orElse(null));
                    fi.put("unique", readAnnoBoolValue(f, "Column", "unique").orElse(null));
                    ((List<Map<String, Object>>) e.get("fields")).add(fi);
                }
            }

            out.add(e);
        }

        log("JPA entities detected (after filters): " + out.size());
        return out;
    }

    // Build a minimal list (no "fields")
    private List<Map<String, Object>> buildMinimal(List<Map<String, Object>> full) {
        List<Map<String, Object>> min = new ArrayList<>();
        for (Map<String, Object> e : full) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", (String) e.get("name"));
            m.put("kind", (String) e.get("kind"));
            m.put("table", (String) e.get("table"));
            m.put("idField", (String) e.get("idField"));
            // no fields
            min.add(m);
        }
        return min;
    }

    // ---------------- access mode detection ----------------

    private enum AccessMode { FIELD, PROPERTY }

    /** Decide access mode: if any getter has JPA annotations, prefer PROPERTY; else FIELD. */
    private AccessMode detectAccessMode(CtClass<?> clazz) {
        boolean getterHasJpaAnno = clazz.getMethods().stream()
                .filter(this::isGetter)
                .anyMatch(this::hasAnyJpaMapping);
        return getterHasJpaAnno ? AccessMode.PROPERTY : AccessMode.FIELD;
    }

    private boolean hasAnyJpaMapping(CtModifiable el) {
        return hasAnnoEnding(el, "Id")
                || hasAnnoEnding(el, "EmbeddedId")
                || hasAnnoEnding(el, "Column")
                || hasAnnoEnding(el, "OneToOne")
                || hasAnnoEnding(el, "OneToMany")
                || hasAnnoEnding(el, "ManyToOne")
                || hasAnnoEnding(el, "ManyToMany");
    }

    private boolean isGetter(CtMethod<?> m) {
        if (!m.getParameters().isEmpty()) return false;
        if (m.getType() == null || "void".equalsIgnoreCase(m.getType().getSimpleName())) return false;
        String n = m.getSimpleName();
        if (n.startsWith("get") && n.length() > 3) return true;
        if (n.startsWith("is")  && n.length() > 2 && isBooleanType(m.getType())) return true;
        return false;
    }

    private boolean isBooleanType(CtTypeReference<?> t) {
        if (t == null) return false;
        String q = safeTypeName(t);
        return "boolean".equals(q) || "java.lang.Boolean".equals(q) || "Boolean".equals(q);
    }

    private String propNameFromGetter(String getter) {
        if (getter.startsWith("get")) return decap(getter.substring(3));
        if (getter.startsWith("is"))  return decap(getter.substring(2));
        return getter;
    }

    private String decap(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    // ---------------- filters & helpers ----------------

    private String detectKind(CtClass<?> clazz) {
        if (hasAnnoEnding(clazz, "Entity")) return "Entity";
        if (hasAnnoEnding(clazz, "Embeddable")) return "Embeddable";
        if (hasAnnoEnding(clazz, "MappedSuperclass")) return "MappedSuperclass";
        return null;
    }

    private static boolean hasAnnoEnding(CtModifiable el, String suffix) {
        return el.getAnnotations().stream().anyMatch(a -> {
            var at = a.getAnnotationType();
            String q = at != null ? at.getQualifiedName() : null;
            return q != null && q.endsWith(suffix);
        });
    }

    private boolean isRelationField(CtField<?> f) {
        return hasAnnoEnding(f, "OneToOne") || hasAnnoEnding(f, "OneToMany")
                || hasAnnoEnding(f, "ManyToOne") || hasAnnoEnding(f, "ManyToMany");
    }

    private boolean isRelationMethod(CtMethod<?> m) {
        return hasAnnoEnding(m, "OneToOne") || hasAnnoEnding(m, "OneToMany")
                || hasAnnoEnding(m, "ManyToOne") || hasAnnoEnding(m, "ManyToMany");
    }

    private boolean isLoggerType(CtTypeReference<?> t) {
        if (t == null) return false;
        String q = safeTypeName(t);
        return q.startsWith("org.apache.log4j.Logger")
                || q.startsWith("org.slf4j.Logger")
                || q.endsWith(".Logger");
    }

    private boolean skipByName(String name) {
        if (name == null) return true;
        if ("serialVersionUID".equals(name)) return true;
        if (name.equals(name.toUpperCase()) && name.contains("_")) return true; // ALL_CAPS heuristic
        if ("LOG".equals(name) || "LOGGER".equals(name)) return true;
        return false;
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

    // ---------------- JSON (manual, no external deps) ----------------

    private String toJson(List<Map<String, Object>> entities) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"entities\":[");
        for (int i = 0; i < entities.size(); i++) {
            Map<String, Object> e = entities.get(i);
            if (i > 0) sb.append(",");
            sb.append("{");
            kv(sb, "name", (String) e.get("name")).append(",");
            kv(sb, "kind", (String) e.get("kind")).append(",");
            kv(sb, "table", (String) e.get("table")).append(",");
            kv(sb, "idField", (String) e.get("idField"));

            // Optional fields (only if present)
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> fields = (List<Map<String, Object>>) e.get("fields");
            if (fields != null) {
                sb.append(",\"fields\":[");
                for (int j = 0; j < fields.size(); j++) {
                    Map<String, Object> f = fields.get(j);
                    if (j > 0) sb.append(",");
                    sb.append("{");
                    kv(sb, "name", (String) f.get("name")).append(",");
                    kv(sb, "javaType", (String) f.get("javaType")).append(",");
                    kv(sb, "column", (String) f.get("column")).append(",");
                    kv(sb, "nullable", (Boolean) f.get("nullable")).append(",");
                    kv(sb, "length", (Integer) f.get("length")).append(",");
                    kv(sb, "unique", (Boolean) f.get("unique"));
                    sb.append("}");
                }
                sb.append("]");
            }

            sb.append("}");
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
