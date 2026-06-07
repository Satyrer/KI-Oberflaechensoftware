package kioberflaeche.storage;

import kioberflaeche.model.ChatMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
                    .filter(this::isChatStorageEntry)
                    .sorted(Comparator.comparing(this::lastModified).reversed())
                    .map(this::loadUnchecked)
                    .toList();
        }
    }

    public void save(ChatSession session) throws IOException {
        ensureDirectory();
        Path path = storagePathFor(session);
        Path chatFile = Files.isDirectory(path) ? path.resolve("chat.txt") : path;
        Files.writeString(chatFile, serialize(session), StandardCharsets.UTF_8);
    }

    public Path export(ChatSession session, Path targetDirectory) throws IOException {
        Files.createDirectories(targetDirectory);
        Path target = uniqueExportPath(session, targetDirectory);
        Files.writeString(target, serialize(session), StandardCharsets.UTF_8);
        return target;
    }

    public Path saveInFolder(ChatSession session, List<StoredFile> files) throws IOException {
        ensureDirectory();
        Path folder = directory.resolve(sanitizeFileName(session.id()));
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("chat.txt"), serialize(session), StandardCharsets.UTF_8);

        for (StoredFile file : files) {
            Path target = uniqueFilePath(folder, sanitizeFileName(file.fileName()));
            Files.write(target, file.content());
        }
        return folder;
    }

    public void delete(ChatSession session) throws IOException {
        Path path = storagePathFor(session);
        if (Files.isDirectory(path)) {
            deleteDirectory(path);
        } else {
            Files.deleteIfExists(path);
        }
    }

    private String serialize(ChatSession session) {
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

        return text.toString();
    }

    private Path storagePathFor(ChatSession session) {
        Path folder = directory.resolve(sanitizeFileName(session.id()));
        if (Files.isRegularFile(folder.resolve("chat.txt"))) {
            return folder;
        }
        return directory.resolve(session.id() + ".txt");
    }

    private Path uniqueExportPath(ChatSession session, Path targetDirectory) throws IOException {
        String baseName = sanitizeFileName(session.title().isBlank() ? "Chat" : session.title());
        if (baseName.isBlank()) {
            baseName = "Chat";
        }

        Path target = targetDirectory.resolve(baseName + ".txt");
        int counter = 2;
        while (Files.exists(target)) {
            target = targetDirectory.resolve(baseName + "-" + counter + ".txt");
            counter++;
        }
        return target;
    }

    private Path uniqueFilePath(Path targetDirectory, String fileName) throws IOException {
        Files.createDirectories(targetDirectory);
        String safeName = fileName == null || fileName.isBlank() ? "datei.md" : fileName;
        Path target = targetDirectory.resolve(safeName);
        int dot = safeName.lastIndexOf('.');
        String name = dot > 0 ? safeName.substring(0, dot) : safeName;
        String extension = dot > 0 ? safeName.substring(dot) : "";
        int counter = 2;
        while (Files.exists(target)) {
            target = targetDirectory.resolve(name + "-" + counter + extension);
            counter++;
        }
        return target;
    }

    private String sanitizeFileName(String value) {
        return value.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    private ChatSession loadUnchecked(Path path) {
        try {
            return load(path);
        } catch (IOException e) {
            throw new IllegalStateException("Chat konnte nicht gelesen werden: " + path, e);
        }
    }

    private ChatSession load(Path path) throws IOException {
        Path chatFile = Files.isDirectory(path) ? path.resolve("chat.txt") : path;
        List<String> lines = Files.readAllLines(chatFile, StandardCharsets.UTF_8);
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

    private boolean isChatStorageEntry(Path path) {
        String fileName = path.getFileName().toString();
        return fileName.endsWith(".txt") || Files.isRegularFile(path.resolve("chat.txt"));
    }

    private void deleteDirectory(Path folder) throws IOException {
        List<Path> paths = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(folder)) {
            stream.sorted(Comparator.reverseOrder()).forEach(paths::add);
        }
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }

    public record StoredFile(String fileName, byte[] content) {
    }
}
