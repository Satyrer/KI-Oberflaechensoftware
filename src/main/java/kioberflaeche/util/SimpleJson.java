package kioberflaeche.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SimpleJson {
    private SimpleJson() {
    }

    public static Object parse(String text) {
        return new Parser(text).parse();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asArray(Object value) {
        return value instanceof List<?> list ? (List<Object>) list : List.of();
    }

    public static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static final class Parser {
        private final String text;
        private int index;

        private Parser(String text) {
            this.text = text == null ? "" : text;
        }

        private Object parse() {
            Object value = parseValue();
            skipWhitespace();
            if (index != text.length()) {
                throw error("Unerwarteter Inhalt nach JSON-Ende");
            }
            return value;
        }

        private Object parseValue() {
            skipWhitespace();
            if (index >= text.length()) {
                throw error("Unerwartetes JSON-Ende");
            }
            char current = text.charAt(index);
            return switch (current) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> object = new LinkedHashMap<>();
            skipWhitespace();
            if (consume('}')) {
                return object;
            }
            do {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                object.put(key, parseValue());
                skipWhitespace();
            } while (consume(','));
            expect('}');
            return object;
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> array = new ArrayList<>();
            skipWhitespace();
            if (consume(']')) {
                return array;
            }
            do {
                array.add(parseValue());
                skipWhitespace();
            } while (consume(','));
            expect(']');
            return array;
        }

        private String parseString() {
            expect('"');
            StringBuilder value = new StringBuilder();
            while (index < text.length()) {
                char current = text.charAt(index++);
                if (current == '"') {
                    return value.toString();
                }
                if (current == '\\') {
                    value.append(parseEscape());
                } else {
                    value.append(current);
                }
            }
            throw error("Nicht geschlossene JSON-Zeichenkette");
        }

        private char parseEscape() {
            if (index >= text.length()) {
                throw error("Nicht geschlossene JSON-Escape-Sequenz");
            }
            char escaped = text.charAt(index++);
            return switch (escaped) {
                case '"' -> '"';
                case '\\' -> '\\';
                case '/' -> '/';
                case 'b' -> '\b';
                case 'f' -> '\f';
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case 'u' -> parseUnicode();
                default -> throw error("Unbekannte JSON-Escape-Sequenz");
            };
        }

        private char parseUnicode() {
            if (index + 4 > text.length()) {
                throw error("Unvollstaendige Unicode-Escape-Sequenz");
            }
            String hex = text.substring(index, index + 4);
            index += 4;
            return (char) Integer.parseInt(hex, 16);
        }

        private Object parseNumber() {
            int start = index;
            if (consume('-')) {
                // optional minus
            }
            while (index < text.length() && Character.isDigit(text.charAt(index))) {
                index++;
            }
            if (consume('.')) {
                while (index < text.length() && Character.isDigit(text.charAt(index))) {
                    index++;
                }
            }
            if (index < text.length() && (text.charAt(index) == 'e' || text.charAt(index) == 'E')) {
                index++;
                if (index < text.length() && (text.charAt(index) == '+' || text.charAt(index) == '-')) {
                    index++;
                }
                while (index < text.length() && Character.isDigit(text.charAt(index))) {
                    index++;
                }
            }
            String raw = text.substring(start, index);
            if (raw.isBlank() || "-".equals(raw)) {
                throw error("Ungueltige JSON-Zahl");
            }
            return raw.contains(".") || raw.contains("e") || raw.contains("E")
                    ? Double.parseDouble(raw)
                    : Long.parseLong(raw);
        }

        private Object parseLiteral(String literal, Object value) {
            if (!text.startsWith(literal, index)) {
                throw error("Ungueltiges JSON-Literal");
            }
            index += literal.length();
            return value;
        }

        private void skipWhitespace() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
        }

        private boolean consume(char expected) {
            if (index < text.length() && text.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            if (!consume(expected)) {
                throw error("Erwartet: " + expected);
            }
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " bei Position " + index);
        }
    }
}
