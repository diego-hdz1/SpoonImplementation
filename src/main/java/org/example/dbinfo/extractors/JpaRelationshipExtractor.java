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
 * Extracts JPA relationships into relationships.json.
 */
public final class JpaRelationshipExtractor {

    public void run(CtModel model, Path outDir) throws Exception {
        List<RelationInfo> relations = extract(model);
        String json = JsonUtil.pretty(toJson(relations));
        Path file = outDir.resolve("relationships.json");
        ensureDir(file.getParent());
        Files.writeString(file, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        log("relationships.json written: " + file.toAbsolutePath());
    }

    // ---------------- core extraction ----------------

    private List<RelationInfo> extract(CtModel model) {
        List<RelationInfo> out = new ArrayList<>();

        for (var t : model.getAllTypes()) {
            if (!(t instanceof CtClass<?>)) continue;
            CtClass<?> clazz = (CtClass<?>) t;
            if (!hasAnnoEnding(clazz, "Entity")) continue;

            for (CtField<?> f : clazz.getFields()) {
                String kind = relationKind(f);
                if (kind == null) continue;

                RelationInfo r = new RelationInfo();
                r.source = clazz.getQualifiedName();
                r.kind = kind;

                // target entity (annotation targetEntity or field type/generic)
                r.target = resolveTargetEntity(f).orElseGet(() -> safeTypeName(targetTypeFromField(f.getType())));

                // mappedBy determines inverse side
                r.mappedBy = readAnnoStringValue(f, kind, "mappedBy").orElse(null);
                r.owningSide = (r.mappedBy == null || r.mappedBy.isBlank());

                // cascade / fetch / optional / orphanRemoval
                r.cascade = readAnnoEnumArray(f, kind, "cascade").orElse(null);
                r.fetch = readAnnoEnumValue(f, kind, "fetch").orElse(null);
                r.optional = readAnnoBoolValue(f, kind, "optional").orElse(null);
                r.orphanRemoval = readAnnoBoolValue(f, kind, "orphanRemoval").orElse(null);

                // @JoinColumn / @JoinTable
                JoinColumn jc = readJoinColumn(f);
                if (jc != null) r.joinColumn = jc;

                JoinTable jt = readJoinTable(f);
                if (jt != null) r.joinTable = jt;

                out.add(r);
            }
        }

        log("JPA relationships detected: " + out.size());
        return out;
    }

    // ---------------- helpers: annotations & types ----------------

    private static boolean hasAnnoEnding(CtModifiable el, String suffix) {
        return el.getAnnotations().stream().anyMatch(a -> {
            var at = a.getAnnotationType();
            String q = at != null ? at.getQualifiedName() : null;
            return q != null && q.endsWith(suffix);
        });
    }

    private static String relationKind(CtField<?> f) {
        if (hasAnnoEnding(f, "OneToOne")) return "OneToOne";
        if (hasAnnoEnding(f, "OneToMany")) return "OneToMany";
        if (hasAnnoEnding(f, "ManyToOne")) return "ManyToOne";
        if (hasAnnoEnding(f, "ManyToMany")) return "ManyToMany";
        return null;
    }

    private Optional<String> resolveTargetEntity(CtField<?> f) {
        for (String anno : List.of("OneToOne", "OneToMany", "ManyToOne", "ManyToMany")) {
            Optional<String> te = readAnnoClassValue(f, anno, "targetEntity");
            if (te.isPresent()) return te;
        }
        return Optional.empty();
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
                .map(JpaRelationshipExtractor::literalString)
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

    private static Optional<String> readAnnoEnumValue(CtModifiable el, String annoSuffix, String key) {
        return el.getAnnotations().stream()
                .filter(a -> {
                    var at = a.getAnnotationType();
                    String q = at != null ? at.getQualifiedName() : null;
                    return q != null && q.endsWith(annoSuffix);
                })
                .map(a -> a.getValues().get(key))
                .filter(Objects::nonNull)
                .map(Object::toString)
                .findFirst();
    }

    private static Optional<List<String>> readAnnoEnumArray(CtModifiable el, String annoSuffix, String key) {
        return el.getAnnotations().stream()
                .filter(a -> {
                    var at = a.getAnnotationType();
                    String q = at != null ? at.getQualifiedName() : null;
                    return q != null && q.endsWith(annoSuffix);
                })
                .map(a -> a.getValues().get(key))
                .filter(Objects::nonNull)
                .map(Object::toString)
                .map(s -> {
                    String trimmed = s.trim();
                    if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                        String inner = trimmed.substring(1, trimmed.length() - 1).trim();
                        if (inner.isEmpty()) return Collections.<String>emptyList();
                        List<String> list = new ArrayList<>();
                        for (String part : inner.split(",")) list.add(part.trim());
                        return list;
                    }
                    return Collections.singletonList(trimmed);
                })
                .findFirst();
    }

    private static Optional<String> readAnnoClassValue(CtModifiable el, String annoSuffix, String key) {
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
                .map(v -> v.endsWith(".class") ? v.substring(0, v.length() - 6) : v)
                .findFirst();
    }

    private static CtTypeReference<?> targetTypeFromField(CtTypeReference<?> fieldType) {
        if (fieldType == null) return null;
        List<CtTypeReference<?>> args = fieldType.getActualTypeArguments();
        if (args != null && !args.isEmpty()) return args.get(0);
        return fieldType;
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

    // ---------------- join metadata readers ----------------

    private JoinColumn readJoinColumn(CtField<?> f) {
        return f.getAnnotations().stream()
                .filter(a -> {
                    var at = a.getAnnotationType();
                    String q = at != null ? at.getQualifiedName() : null;
                    return q != null && q.endsWith("JoinColumn");
                })
                .findFirst()
                .map(a -> {
                    JoinColumn jc = new JoinColumn();
                    var vName = a.getValues().get("name");
                    var vRef  = a.getValues().get("referencedColumnName");
                    jc.name = literalString(vName);
                    jc.referencedColumnName = literalString(vRef);
                    return jc;
                })
                .orElse(null);
    }

    private JoinTable readJoinTable(CtField<?> f) {
        return f.getAnnotations().stream()
                .filter(a -> {
                    var at = a.getAnnotationType();
                    String q = at != null ? at.getQualifiedName() : null;
                    return q != null && q.endsWith("JoinTable");
                })
                .findFirst()
                .map(a -> {
                    JoinTable jt = new JoinTable();
                    jt.name = literalString(a.getValues().get("name"));
                    jt.joinColumns = parseJoinColumnsArray(a.getValues().get("joinColumns"));
                    jt.inverseJoinColumns = parseJoinColumnsArray(a.getValues().get("inverseJoinColumns"));
                    return jt;
                })
                .orElse(null);
    }

    private List<JoinColumn> parseJoinColumnsArray(Object attr) {
        if (attr == null) return null;
        String s = attr.toString().trim();
        if (!s.startsWith("{") || !s.endsWith("}")) {
            JoinColumn single = parseInlineJoinColumn(s);
            return single == null ? null : List.of(single);
        }
        String inner = s.substring(1, s.length() - 1).trim();
        if (inner.isEmpty()) return new ArrayList<>();

        List<JoinColumn> list = new ArrayList<>();
        int start = 0, depth = 0;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '{') depth++;
            if (c == '}') depth--;
            if (c == ',' && depth == 0) {
                String chunk = inner.substring(start, i).trim();
                JoinColumn jc = parseInlineJoinColumn(chunk);
                if (jc != null) list.add(jc);
                start = i + 1;
            }
        }
        String last = inner.substring(start).trim();
        JoinColumn lastJc = parseInlineJoinColumn(last);
        if (lastJc != null) list.add(lastJc);

        return list;
    }

    private JoinColumn parseInlineJoinColumn(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (!t.contains("JoinColumn")) return null;
        JoinColumn jc = new JoinColumn();
        jc.name = extractNamedString(t, "name");
        jc.referencedColumnName = extractNamedString(t, "referencedColumnName");
        return jc;
    }

    private String extractNamedString(String text, String key) {
        String needle = key + "=";
        int i = text.indexOf(needle);
        if (i < 0) return null;
        int j = text.indexOf('"', i + needle.length());
        if (j < 0) return null;
        int k = text.indexOf('"', j + 1);
        if (k < 0) return null;
        return text.substring(j + 1, k);
    }

    // ---------------- DTOs ----------------

    static final class RelationInfo {
        String source;
        String kind;                  // OneToOne | OneToMany | ManyToOne | ManyToMany
        String target;
        Boolean owningSide;
        String mappedBy;
        List<String> cascade;
        String fetch;
        Boolean optional;
        Boolean orphanRemoval;
        JoinColumn joinColumn;
        JoinTable joinTable;
    }

    static final class JoinColumn {
        String name;
        String referencedColumnName;
    }

    static final class JoinTable {
        String name;
        List<JoinColumn> joinColumns;
        List<JoinColumn> inverseJoinColumns;
    }

    // ---------------- JSON (manual, no external deps) ----------------

    private String toJson(List<RelationInfo> list) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"relationships\":[");
        for (int i = 0; i < list.size(); i++) {
            RelationInfo r = list.get(i);
            if (i > 0) sb.append(",");
            sb.append("{");
            kv(sb, "source", r.source).append(",");
            kv(sb, "kind", r.kind).append(",");
            kv(sb, "target", r.target).append(",");
            kv(sb, "owningSide", r.owningSide).append(",");
            kv(sb, "mappedBy", r.mappedBy).append(",");
            // cascade
            sb.append("\"cascade\":");
            if (r.cascade == null) sb.append("null,");
            else {
                sb.append("[");
                for (int k = 0; k < r.cascade.size(); k++) {
                    if (k > 0) sb.append(",");
                    sb.append(string(r.cascade.get(k)));
                }
                sb.append("],");
            }
            kv(sb, "fetch", r.fetch).append(",");
            kv(sb, "optional", r.optional).append(",");
            kv(sb, "orphanRemoval", r.orphanRemoval).append(",");

            // joinColumn
            sb.append("\"joinColumn\":");
            if (r.joinColumn == null) sb.append("null,");
            else {
                sb.append("{");
                kv(sb, "name", r.joinColumn.name).append(",");
                kv(sb, "referencedColumnName", r.joinColumn.referencedColumnName);
                sb.append("},");
            }

            // joinTable
            sb.append("\"joinTable\":");
            if (r.joinTable == null) sb.append("null");
            else {
                sb.append("{");
                kv(sb, "name", r.joinTable.name).append(",");
                sb.append("\"joinColumns\":");
                if (r.joinTable.joinColumns == null) sb.append("null,");
                else {
                    sb.append("[");
                    for (int j = 0; j < r.joinTable.joinColumns.size(); j++) {
                        if (j > 0) sb.append(",");
                        JoinColumn jc = r.joinTable.joinColumns.get(j);
                        sb.append("{");
                        kv(sb, "name", jc.name).append(",");
                        kv(sb, "referencedColumnName", jc.referencedColumnName);
                        sb.append("}");
                    }
                    sb.append("],");
                }
                sb.append("\"inverseJoinColumns\":");
                if (r.joinTable.inverseJoinColumns == null) sb.append("null");
                else {
                    sb.append("[");
                    for (int j = 0; j < r.joinTable.inverseJoinColumns.size(); j++) {
                        if (j > 0) sb.append(",");
                        JoinColumn jc = r.joinTable.inverseJoinColumns.get(j);
                        sb.append("{");
                        kv(sb, "name", jc.name).append(",");
                        kv(sb, "referencedColumnName", jc.referencedColumnName);
                        sb.append("}");
                    }
                    sb.append("]");
                }
                sb.append("}");
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
        System.out.println("[JpaRelationshipExtractor] " + s);
    }
}

