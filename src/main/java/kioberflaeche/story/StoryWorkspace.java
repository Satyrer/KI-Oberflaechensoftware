package kioberflaeche.story;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Local files and immutable task input. No VM files or network calls. */
public final class StoryWorkspace {
    private StoryWorkspace() {}

    public record Snapshot(Path project, List<SchreibAiWorkflowClient.StoryDocument> documents,
                           List<SchreibAiWorkflowClient.StoryDocument> contextDocuments,
                           Map<String, String> originals, int excludedFiles) {}

    public static Path createProject(Path workspace, String name, String briefing) throws IOException {
        String clean = name == null ? "" : name.strip();
        if (clean.isBlank() || clean.equals(".") || clean.equals("..") || clean.matches(".*[<>:\"/\\\\|?*\\p{Cntrl}].*")
                || clean.endsWith(".") || clean.endsWith(" ")
                || clean.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?")) {
            throw new IOException("Bitte einen gueltigen neuen Projektnamen ohne Pfadzeichen angeben.");
        }
        if (briefing == null || briefing.isBlank()) throw new IOException("Bitte ein freies Story-Briefing eingeben.");
        Files.createDirectories(workspace);
        Path root = workspace.toRealPath();
        Path project = root.resolve(clean);
        // CREATE_DIRECTORY fails for any existing name; existing projects are never initialized over.
        Files.createDirectory(project);
        Path sources = Files.createDirectory(project.resolve("sources"));
        Files.writeString(sources.resolve("story-briefing.md"), "# Story-Briefing\n\n" + briefing + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        return project;
    }

    public static Path resolve(Path project, String relative) throws IOException {
        if (relative == null || relative.isBlank()) throw new IOException("Der Dateipfad fehlt.");
        Path base = project.toRealPath();
        Path target = base.resolve(relative.replace('\\', '/')).normalize();
        if (target.equals(base) || !target.startsWith(base)) throw new IOException("Dateipfad verlaesst das Arbeitsprojekt: " + relative);
        for (Path part = target; part != null && part.startsWith(base); part = part.getParent()) {
            if (Files.exists(part, LinkOption.NOFOLLOW_LINKS) && !part.toRealPath().startsWith(base)) {
                throw new IOException("Dateiverknuepfung verlaesst das Arbeitsprojekt: " + relative);
            }
        }
        return target;
    }

    public static boolean internal(String path) {
        // Document originals, extraction results and versions require a separate reviewed adapter.
        // Resolve dot segments so an old proposal cannot write through sources/../documents/.
        List<String> normalized = new ArrayList<>();
        for (String part : path.replace('\\', '/').toLowerCase(Locale.ROOT).split("/")) {
            if (part.startsWith(".schreib-ai-")) return true;
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..") && !normalized.isEmpty() && !normalized.getLast().equals("..")) normalized.removeLast();
            else normalized.add(part);
        }
        return !normalized.isEmpty() && normalized.getFirst().equals("documents");
    }

    private static boolean internalTarget(Path base, Path target) throws IOException {
        if (internal(base.relativize(target).toString())) return true;
        // Existing file aliases and directory junctions must not bypass the reserved area.
        for (Path part = target; part != null && part.startsWith(base); part = part.getParent()) {
            if (Files.exists(part, LinkOption.NOFOLLOW_LINKS)
                    && internal(base.relativize(part.toRealPath()).toString())) return true;
        }
        return false;
    }

    /** Same target contract as the CPU story pipeline; no automatic append semantics. */
    public static String sceneTarget(Path project, String relative) throws IOException {
        if (relative==null || !relative.equals(relative.strip())) throw new IOException("Der Zielpfad darf nicht mit Leerzeichen beginnen oder enden.");
        String path=relative.replace('\\','/');
        if (!path.startsWith("manuscript/") || !path.endsWith(".md") || path.codePointCount(0,path.length())>500
                || automaticCopy(path) || internal(path)) throw new IOException("Bitte eine Markdown-Szenendatei unter manuscript/ angeben, zum Beispiel manuscript/szene-001.md.");
        for(String part:path.split("/",-1)) {
            if(part.isEmpty() || part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ")
                    || part.length()>255 || part.matches(".*[<>:\"\\\\|?*\\p{Cntrl}].*")
                    || part.matches("(?i)(CON|PRN|AUX|NUL|CONIN\\$|CONOUT\\$|COM[1-9¹²³]|LPT[1-9¹²³])(?:\\..*)?")) {
                throw new IOException("Der Zielpfad enthaelt einen unter Windows ungueltigen oder reservierten Dateinamen.");
            }
        }
        resolve(project,path);
        return path;
    }

    public static String freshSceneTarget(Path project) throws IOException {
        for(int index=1;index<1_000_000;index++) {
            String candidate=String.format(Locale.ROOT,"manuscript/szene-%03d.md",index);
            if(!Files.exists(resolve(project,candidate))) return candidate;
        }
        return "manuscript/szene-"+UUID.randomUUID()+".md";
    }

    public static String instruction(String text, String selectedPath) {
        String value=text==null?"":text.strip();
        return selectedPath==null ? value : "Arbeitsgrundlage im Projekt: "+selectedPath+"\n\n"+value;
    }

    public static int instructionBytes(String instruction) { return instruction.getBytes(StandardCharsets.UTF_8).length; }

    public static void validateInstruction(String instruction) throws IOException {
        if(instructionBytes(instruction)>1200) throw new IOException("Der Auftrag umfasst "+instructionBytes(instruction)
                +" von maximal 1200 UTF-8-Bytes (einschliesslich Dateiverweis). Bitte den Auftrag kuerzen und laengere Inhalte als Quelldatei oder Story-Briefing ablegen.");
    }

    public static boolean automaticCopy(String path) {
        String value = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        return internal(value) || value.equals("manuscript/imported-draft.md")
                || value.equals("overview/source-map.md") || value.equals("system/ki-synthesis-raw.md")
                || value.equals("system/pending-updates.md") || value.equals("system/change-log.md")
                || value.startsWith("system/analysis-runs/");
    }

    public static boolean context(String path) {
        String value = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        return List.of("manuscript/", "chapters/", "final/", "overview/", "timeline/", "characters/", "world/", "system/")
                .stream().anyMatch(value::startsWith);
    }

    public static List<StoryFile> files(Path project) throws IOException {
        Path base = project.toRealPath();
        List<StoryFile> result = new ArrayList<>();
        Files.walkFileTree(base, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                return internalTarget(base, directory) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                String relative = base.relativize(file).toString().replace('\\', '/');
                String lower = relative.toLowerCase(Locale.ROOT);
                if (internal(relative) || !(lower.endsWith(".md") || lower.endsWith(".txt")) || !Files.isRegularFile(file)) {
                    return FileVisitResult.CONTINUE;
                }
                resolve(base, relative);
                if (internalTarget(base, file)) return FileVisitResult.CONTINUE;
                String kind = automaticCopy(relative) ? "Automatisch / kein neuer Kontext" : context(relative) ? "Kontext" : "Quelle";
                result.add(new StoryFile(relative, kind, file.getFileName().toString(), Files.size(file), Files.getLastModifiedTime(file).toString()));
                return FileVisitResult.CONTINUE;
            }
        });
        result.sort(Comparator.comparing(StoryFile::path));
        return List.copyOf(result);
    }

    public static Snapshot snapshot(Path project, String editorPath, String editorContent) throws IOException {
        return snapshot(project, editorPath == null || editorContent == null ? Map.of() : Map.of(editorPath, editorContent));
    }

    public static Snapshot snapshot(Path project, Map<String, String> drafts) throws IOException {
        Path base = project.toRealPath();
        List<SchreibAiWorkflowClient.StoryDocument> sources = new ArrayList<>();
        List<SchreibAiWorkflowClient.StoryDocument> contexts = new ArrayList<>();
        Map<String, String> originals = new LinkedHashMap<>();
        int excluded = 0;
        for (StoryFile file : files(base)) {
            String original = Files.readString(resolve(base, file.path()), StandardCharsets.UTF_8);
            originals.put(file.path(), original);
            if (automaticCopy(file.path())) { excluded++; continue; }
            String content = drafts.getOrDefault(file.path(), original);
            var document = new SchreibAiWorkflowClient.StoryDocument(file.path(), content);
            (context(file.path()) ? contexts : sources).add(document);
        }
        return new Snapshot(base, List.copyOf(sources), List.copyOf(contexts), Map.copyOf(originals), excluded);
    }

    public static void writeText(Path project, String relative, String content) throws IOException {
        Path base = project.toRealPath();
        Path target = resolve(base, relative);
        if (internal(relative) || internalTarget(base, target)) throw new IOException("Interne Arbeitsdateien und documents/ koennen nicht als Story-Ziel verwendet werden.");
        Files.createDirectories(target.getParent());
        atomicWrite(target, content);
    }

    public static void atomicWrite(Path target, String content) throws IOException {
        Path temporary = target.resolveSibling("." + target.getFileName() + ".writing-" + UUID.randomUUID());
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static Path backup(Path project, String relative, String operationId) throws IOException {
        Path source = resolve(project, relative);
        if (!Files.isRegularFile(source)) return null;
        Path target = resolve(project, ".schreib-ai-backups/" + operationId + "/" + relative);
        Files.createDirectories(target.getParent());
        if (Files.exists(target)) {
            if (Files.mismatch(source, target) != -1) throw new IOException("Die Sicherung dieses Auftrags darf nicht ueberschrieben werden.");
        } else Files.copy(source, target); // Retrying a partial apply can reuse an identical backup.
        return target;
    }
}
