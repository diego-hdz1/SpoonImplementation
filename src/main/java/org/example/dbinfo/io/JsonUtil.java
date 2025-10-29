package org.example.dbinfo.io;

/**
 * Tiny JSON pretty-printer (no external deps).
 * Best-effort: formats objects/arrays, commas, and string literals safely.
 */
public final class JsonUtil {

    private JsonUtil() {}

    public static String pretty(String json) {
        if (json == null || json.isBlank()) return json;
        StringBuilder out = new StringBuilder(json.length() + 64);
        int indent = 0;
        boolean inString = false;
        boolean escape = false;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);

            if (inString) {
                out.append(c);
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }

            switch (c) {
                case '"':
                    inString = true;
                    out.append(c);
                    break;
                case '{':
                case '[':
                    out.append(c);
                    out.append('\n');
                    indent++;
                    appendIndent(out, indent);
                    break;
                case '}':
                case ']':
                    out.append('\n');
                    indent = Math.max(0, indent - 1);
                    appendIndent(out, indent);
                    out.append(c);
                    break;
                case ',':
                    out.append(c);
                    out.append('\n');
                    appendIndent(out, indent);
                    break;
                case ':':
                    out.append(c).append(' ');
                    break;
                default:
                    // collapse any existing newlines/tabs to single space only if they are insignificant
                    if (c == '\n' || c == '\r' || c == '\t') {
                        // skip
                    } else {
                        out.append(c);
                    }
                    break;
            }
        }
        return out.toString();
    }

    private static void appendIndent(StringBuilder sb, int indent) {
        for (int i = 0; i < indent; i++) sb.append("  ");
    }
}
