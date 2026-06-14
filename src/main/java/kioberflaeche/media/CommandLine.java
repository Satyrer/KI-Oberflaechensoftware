package kioberflaeche.media;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class CommandLine {
    private CommandLine() {
    }

    static List<String> parseTemplate(String template, Path input, Path output, String language) {
        String outputDir = output.getParent() == null ? Path.of(".").toString() : output.getParent().toString();
        String command = template
                .replace("{input}", input.toString())
                .replace("{output}", output.toString())
                .replace("{outputDir}", outputDir)
                .replace("{language}", language == null || language.isBlank() ? "de" : language);
        return split(command);
    }

    private static List<String> split(String command) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        char quote = 0;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if ((c == '"' || c == '\'') && (!quoted || quote == c)) {
                quoted = !quoted;
                quote = quoted ? c : 0;
                continue;
            }
            if (Character.isWhitespace(c) && !quoted) {
                if (!current.isEmpty()) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return parts;
    }
}
