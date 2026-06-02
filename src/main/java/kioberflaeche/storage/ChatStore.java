package kioberflaeche.storage;

import kioberflaeche.model.ChatMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

public class ChatStore {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final Path directory;

    public ChatStore(Path directory) {
        this.directory = directory;
    }

    public ChatSession newSession() {
        return new ChatSession(UUID.randomUUID().toString(), "Neuer Chat");
    }

    public List<ChatSession> loadAll() throws IOException {
        ensureDirectory();
        try (Stream<Path> files = Files.list(directory)) {
            return files
                    .filter(path -> path.getFileName().toString().endsWith(".txt"))
                    .sorted(Comparator.comparing(this::lastModified).reversed())
                    .map(this::loadUnchecked)
                    .toList();
        }
    }

    public void save(ChatSession session) throws IOException {
        ensureDirectory();
        StringBuilder text = new StringBuilder();
        text.append("CHAT_ID: ").append(session.id()).append(System.lineSeparator());
        text.append("TITLE: ").append(session.title()).append(System.lineSeparator());
        text.append(System.lineSeparator());

        for (ChatMessage message : session.messages()) {
            text.append("<<<").append(message.sender().name()).append("|")
                    .append(DATE_FORMAT.format(message.timestamp())).append(">>>")
                    .append(System.lineSeparator());
            text.append(message.text()).append(System.lineSeparator());
            text.append("<<<END>>>").append(System.lineSeparator()).append(System.lineSeparator());
        }

        Files.writeString(directory.resolve(session.id() + ".txt"), text.toString(), StandardCharsets.UTF_8);
    }

    private ChatSession loadUnchecked(Path path) {
        try {
            return load(path);
        } catch (IOException e) {
            throw new IllegalStateException("Chat konnte nicht gelesen werden: " + path, e);
        }
    }

    private ChatSession load(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        String id = valueAfter(lines, "CHAT_ID: ", fallbackId(path));
        String title = valueAfter(lines, "TITLE: ", "Chat");
        ChatSession session = new ChatSession(id, title);

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.startsWith("<<<") || line.equals("<<<END>>>")) {
                continue;
            }

            String header = line.substring(3, line.length() - 3);
            String[] parts = header.split("\\|", 2);
            ChatMessage.Sender sender = ChatMessage.Sender.valueOf(parts[0]);
            LocalDateTime timestamp = parts.length > 1 ? LocalDateTime.parse(parts[1], DATE_FORMAT) : LocalDateTime.now();
            StringBuilder messageText = new StringBuilder();
            i++;
            while (i < lines.size() && !"<<<END>>>".equals(lines.get(i))) {
                if (!messageText.isEmpty()) {
                    messageText.append(System.lineSeparator());
                }
                messageText.append(lines.get(i));
                i++;
            }
            session.addLoaded(new ChatMessage(sender, messageText.toString(), timestamp));
        }

        return session;
    }

    private String valueAfter(List<String> lines, String prefix, String fallback) {
        return lines.stream()
                .filter(line -> line.startsWith(prefix))
                .findFirst()
                .map(line -> line.substring(prefix.length()))
                .orElse(fallback);
    }

    private String fallbackId(Path path) {
        String fileName = path.getFileName().toString();
        return fileName.endsWith(".txt") ? fileName.substring(0, fileName.length() - 4) : fileName;
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private void ensureDirectory() throws IOException {
        Files.createDirectories(directory);
    }
}
