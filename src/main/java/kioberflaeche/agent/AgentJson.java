package kioberflaeche.agent;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded protocol JSON: duplicate keys, invalid Unicode and deep nesting fail closed. */
public final class AgentJson {
    private AgentJson() {}

    public static Object parse(String text) {
        if (text == null || text.length() > 12 * 1024 * 1024) throw new IllegalArgumentException("JSON limit");
        Parser parser = new Parser(text);
        Object result = parser.value(0);
        parser.space();
        if (parser.at != text.length()) throw new IllegalArgumentException("Trailing JSON");
        return result;
    }

    public static String encode(Object value) {
        StringBuilder out = new StringBuilder();
        append(out, value, 0);
        return out.toString();
    }

    private static void append(StringBuilder out, Object value, int depth) {
        if (depth > 32) throw new IllegalArgumentException("JSON depth");
        if (value == null) out.append("null");
        else if (value instanceof Boolean || value instanceof Integer || value instanceof Long || value instanceof BigDecimal) out.append(value);
        else if (value instanceof String text) {
            out.append('"');
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\b' -> out.append("\\b");
                    case '\f' -> out.append("\\f");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c < 32) out.append(String.format("\\u%04x", (int)c));
                        else if (Character.isHighSurrogate(c)) {
                            if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) throw new IllegalArgumentException("Unicode");
                            out.append(c).append(text.charAt(i));
                        } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Unicode");
                        else out.append(c);
                    }
                }
            }
            out.append('"');
        } else if (value instanceof List<?> list) {
            out.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) out.append(',');
                first = false;
                append(out, item, depth + 1);
            }
            out.append(']');
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String)) throw new IllegalArgumentException("JSON key");
                if (!first) out.append(',');
                first = false;
                append(out, entry.getKey(), depth + 1);
                out.append(':');
                append(out, entry.getValue(), depth + 1);
            }
            out.append('}');
        } else throw new IllegalArgumentException("JSON type");
    }

    private static final class Parser {
        final String text;
        int at, items;
        Parser(String text) { this.text = text; }
        void space() {
            while (at < text.length() && " \t\r\n".indexOf(text.charAt(at)) >= 0) at++;
        }
        IllegalArgumentException bad() { return new IllegalArgumentException("Invalid agent JSON"); }
        boolean consume(char c) {
            space();
            if (at < text.length() && text.charAt(at) == c) { at++; return true; }
            return false;
        }
        void expect(char c) { if (!consume(c)) throw bad(); }
        Object value(int depth) {
            if (depth > 32 || ++items > 50000) throw bad();
            space();
            if (at >= text.length()) throw bad();
            char c = text.charAt(at);
            if (c == '"') return string();
            if (c == '{') {
                at++;
                Map<String,Object> result = new LinkedHashMap<>();
                if (consume('}')) return result;
                do {
                    space();
                    String key = string();
                    if (result.containsKey(key)) throw bad();
                    expect(':');
                    result.put(key, value(depth + 1));
                } while (consume(','));
                expect('}');
                return result;
            }
            if (c == '[') {
                at++;
                List<Object> result = new ArrayList<>();
                if (consume(']')) return result;
                do { result.add(value(depth + 1)); } while (consume(','));
                expect(']');
                return result;
            }
            for (String literal : List.of("true", "false", "null")) {
                if (text.startsWith(literal, at)) {
                    at += literal.length();
                    return literal.equals("null") ? null : literal.equals("true");
                }
            }
            int start = at;
            if (at < text.length() && text.charAt(at) == '-') at++;
            if (at >= text.length()) throw bad();
            if (text.charAt(at) == '0') at++;
            else {
                if (text.charAt(at) < '1' || text.charAt(at) > '9') throw bad();
                while (at < text.length() && text.charAt(at) >= '0' && text.charAt(at) <= '9') at++;
            }
            if (at < text.length() && text.charAt(at) == '.') {
                at++;
                int digits = at;
                while (at < text.length() && text.charAt(at) >= '0' && text.charAt(at) <= '9') at++;
                if (at == digits) throw bad();
            }
            if (at < text.length() && (text.charAt(at) == 'e' || text.charAt(at) == 'E')) {
                at++;
                if (at < text.length() && (text.charAt(at) == '+' || text.charAt(at) == '-')) at++;
                int digits = at;
                while (at < text.length() && text.charAt(at) >= '0' && text.charAt(at) <= '9') at++;
                if (at == digits) throw bad();
            }
            if (at - start > 80) throw bad();
            try { return new BigDecimal(text.substring(start, at)); }
            catch (NumberFormatException e) { throw bad(); }
        }
        String string() {
            if (at >= text.length() || text.charAt(at++) != '"') throw bad();
            StringBuilder result = new StringBuilder();
            while (at < text.length()) {
                char c = text.charAt(at++);
                if (c == '"') {
                    String value = result.toString();
                    for (int i = 0; i < value.length(); i++) {
                        if (Character.isHighSurrogate(value.charAt(i))) {
                            if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) throw bad();
                        } else if (Character.isLowSurrogate(value.charAt(i))) throw bad();
                    }
                    return value;
                }
                if (c < 32) throw bad();
                if (c != '\\') { result.append(c); continue; }
                if (at >= text.length()) throw bad();
                char escape = text.charAt(at++);
                switch (escape) {
                    case '"', '\\', '/' -> result.append(escape);
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> {
                        if (at + 4 > text.length()) throw bad();
                        String hex = text.substring(at, at + 4);
                        if (!hex.matches("[0-9a-fA-F]{4}")) throw bad();
                        result.append((char)Integer.parseInt(hex, 16));
                        at += 4;
                    }
                    default -> throw bad();
                }
            }
            throw bad();
        }
    }
}
