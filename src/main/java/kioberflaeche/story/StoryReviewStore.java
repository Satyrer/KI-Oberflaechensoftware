package kioberflaeche.story;

import kioberflaeche.util.SimpleJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One persisted review per task. No merging of different task results. */
public final class StoryReviewStore {
    private StoryReviewStore() {}

    public static final class Change {
        public final String action, path, original;
        public final boolean existed;
        public String content;
        public boolean applied;
        /** Persisted before the target write; frozen until that write has been reconciled. */
        public String writeIntent;
        public Change(String action, String path, String content, String original, boolean existed) {
            this.action=action; this.path=path; this.content=content; this.original=original; this.existed=existed;
        }
        @Override public String toString() { return (applied ? "Uebernommen: " : writeIntent!=null ? "Uebernahme fortsetzen: " : "") + path; }
    }

    public static final class Review {
        public final Path project, file;
        public final String id, createdAt, mode, instruction, raw, summary;
        public final List<Change> changes;
        public final boolean failed, legacy;
        public Review(Path project, Path file, String id, String createdAt, String mode, String instruction,
                      String raw, String summary, List<Change> changes, boolean failed, boolean legacy) {
            this.project=project; this.file=file; this.id=id; this.createdAt=createdAt; this.mode=mode;
            this.instruction=instruction; this.raw=raw; this.summary=summary; this.changes=changes; this.failed=failed; this.legacy=legacy;
        }
        public List<Change> pending() { return changes.stream().filter(change -> !change.applied).toList(); }
        public boolean complete() { return !failed && pending().isEmpty(); }
    }

    public record SavedTask(Path file, String display) {
        @Override public String toString() { return display; }
    }

    public static Review create(StoryWorkspace.Snapshot snapshot, String mode, String instruction,
                                SchreibAiWorkflowClient.Result result) throws IOException {
        String id=UUID.randomUUID().toString();
        Path file=StoryWorkspace.resolve(snapshot.project(), ".schreib-ai-temp/task-"+id+".json");
        List<Change> changes=new ArrayList<>();
        for (var proposed : result.changes()) {
            StoryWorkspace.resolve(snapshot.project(), proposed.path());
            if (StoryWorkspace.internal(proposed.path())) throw new IOException("Der Workflow darf keine internen Arbeitsdateien ersetzen.");
            boolean existed=snapshot.originals().containsKey(proposed.path());
            Path target=StoryWorkspace.resolve(snapshot.project(), proposed.path());
            if (!existed && Files.exists(target)) {
                throw new IOException("Vorgeschlagenes Ziel war kein Bestandteil des festgehaltenen Dateistands: "+proposed.path());
            }
            changes.add(new Change(proposed.action(), proposed.path(), proposed.content(), snapshot.originals().getOrDefault(proposed.path(), ""), existed));
        }
        String summary=result.summary()+(result.warning().isBlank()?"":"\n\nHinweis: "+result.warning());
        Review review=new Review(snapshot.project(), file, id, Instant.now().toString(), mode, instruction,
                result.raw(), summary, changes, false, false);
        save(review);
        return review;
    }

    public static Review failure(Path project, String mode, String instruction, String raw, String message) throws IOException {
        String id=UUID.randomUUID().toString();
        Review review=new Review(project.toRealPath(), StoryWorkspace.resolve(project,".schreib-ai-temp/task-"+id+".json"), id,
                Instant.now().toString(), mode, instruction, raw, message, new ArrayList<>(), true, false);
        save(review);
        return review;
    }

    public static void save(Review review) throws IOException {
        Path file=StoryWorkspace.resolve(review.project, review.project.relativize(review.file).toString());
        Files.createDirectories(file.getParent());
        List<Object> changes=new ArrayList<>();
        for (Change change: review.changes) {
            Map<String,Object> row=new LinkedHashMap<>();
            row.put("action",change.action); row.put("path",change.path); row.put("content",change.content);
            row.put("original",change.original); row.put("originalHash",StoryWorkspace.hash(change.original));
            row.put("existed",change.existed); row.put("applied",change.applied);
            row.put("writeIntent",change.writeIntent);
            changes.add(row);
        }
        Map<String,Object> root=new LinkedHashMap<>();
        root.put("reviewVersion",3); root.put("id",review.id); root.put("createdAt",review.createdAt);
        root.put("projectId",review.project.getFileName().toString()); root.put("mode",review.mode);
        root.put("instruction",review.instruction); root.put("response",review.raw); root.put("summary",review.summary);
        root.put("failed",review.failed); root.put("legacy",review.legacy); root.put("changes",changes);
        StoryWorkspace.atomicWrite(file,json(root));
    }

    public static void apply(Review review, Change change) throws IOException {
        if (review.failed || change.applied || !review.changes.contains(change)) throw new IOException("Dieser Vorschlag ist nicht zur Uebernahme verfuegbar.");
        Path target=StoryWorkspace.resolve(review.project,change.path);
        boolean exists=Files.exists(target);
        if (change.writeIntent!=null) {
            if (!change.content.equals(change.writeIntent)) throw new IOException("Eine unterbrochene Uebernahme muss zuerst abgeschlossen werden: "+change.path);
            if (exists && Files.isRegularFile(target)
                    && StoryWorkspace.hash(Files.readString(target,StandardCharsets.UTF_8)).equals(StoryWorkspace.hash(change.writeIntent))) {
                markApplied(review,change); // Target write succeeded before an interruption; never write it twice.
                return;
            }
        }
        if (exists!=change.existed || (exists && (!Files.isRegularFile(target)
                || !StoryWorkspace.hash(Files.readString(target,StandardCharsets.UTF_8)).equals(StoryWorkspace.hash(change.original))))) {
            throw new IOException("Dateikonflikt: "+change.path+" wurde seit dem Auftrag geaendert. Vorhandene Datei bleibt unveraendert.");
        }
        change.writeIntent=change.content;
        save(review); // Durable intent and complete evidence precede the target write.
        if (exists) StoryWorkspace.backup(review.project,change.path,review.id);
        StoryWorkspace.writeText(review.project,change.path,change.content);
        markApplied(review,change);
    }

    private static void markApplied(Review review, Change change) throws IOException {
        change.applied=true;
        try { save(review); }
        catch(IOException error) {
            change.applied=false; // Never archive a merely in-memory success after a failed journal save.
            throw new IOException("Die Datei "+change.path+" entspricht bereits dem Vorschlag; die Bestaetigung konnte noch nicht gespeichert werden. Uebernahme nach Beheben des Speicherfehlers fortsetzen.",error);
        } catch(RuntimeException error) {
            change.applied=false;
            throw error;
        }
    }

    public static void archiveCompleted(Review review) throws IOException {
        if (!review.complete() || !Files.isRegularFile(review.file)) return;
        if (!open(review.project,review.file).complete()) throw new IOException("Der gesicherte Zwischenstand bestaetigt die Uebernahme noch nicht.");
        Path archive=StoryWorkspace.resolve(review.project,".schreib-ai-history/"+review.file.getFileName());
        Files.createDirectories(archive.getParent());
        Files.move(review.file,archive); // Only this task leaves the pending list; no unrelated temp file is deleted.
    }

    public static List<SavedTask> list(Path project) throws IOException {
        Path directory=StoryWorkspace.resolve(project,".schreib-ai-temp");
        if (!Files.isDirectory(directory)) return List.of();
        List<Path> paths;
        try (var stream=Files.list(directory)) {
            paths=stream.filter(path->path.getFileName().toString().endsWith(".json") && !path.getFileName().toString().equals("editor-drafts.json"))
                    .sorted(Comparator.comparing(StoryReviewStore::modified).reversed()).toList();
        }
        List<SavedTask> result=new ArrayList<>();
        for (Path file:paths) {
            String label=file.getFileName().toString();
            try {
                Map<String,Object> root=SimpleJson.asObject(SimpleJson.parse(Files.readString(file,StandardCharsets.UTF_8)));
                if (root.containsKey("reviewVersion")) {
                    label=SimpleJson.asString(root.get("createdAt"))+" – "+SimpleJson.asString(root.get("mode"))
                            +(Boolean.TRUE.equals(root.get("failed"))?" (fehlgeschlagen)":"");
                } else label="Alter Zwischenstand: "+label;
            } catch (RuntimeException ignored) { label="Nicht lesbar: "+label; }
            result.add(new SavedTask(file,label));
        }
        return List.copyOf(result);
    }

    private static java.nio.file.attribute.FileTime modified(Path path) {
        try { return Files.getLastModifiedTime(path); }
        catch(IOException error) { return java.nio.file.attribute.FileTime.fromMillis(0); }
    }

    public static Review open(Path project, Path file) throws IOException {
        Path base=project.toRealPath();
        Path checked=StoryWorkspace.resolve(base,base.relativize(file.toAbsolutePath().normalize()).toString());
        if (!checked.getParent().equals(base.resolve(".schreib-ai-temp"))) throw new IOException("Kein Zwischenstand dieses Projekts.");
        String raw=Files.readString(checked,StandardCharsets.UTF_8);
        Map<String,Object> root;
        try { root=SimpleJson.asObject(SimpleJson.parse(raw)); }
        catch(RuntimeException error) { throw new IOException("Zwischenstand ist kein lesbares JSON.",error); }
        if (!root.containsKey("reviewVersion")) {
            var result=SchreibAiWorkflowClient.parseResult(raw,base.getFileName().toString());
            List<Change> changes=new ArrayList<>();
            for (var change:result.changes()) {
                Path target=StoryWorkspace.resolve(base,change.path());
                boolean exists=Files.isRegularFile(target);
                changes.add(new Change(change.action(),change.path(),change.content(),exists?Files.readString(target,StandardCharsets.UTF_8):"",exists));
            }
            return new Review(base,checked,UUID.randomUUID().toString(),modified(checked).toInstant().toString(),"Alter Zwischenstand","",raw,
                    "Alter Zwischenstand: Urspruenglicher Dateistand unbekannt. Vergleich zeigt den heutigen Stand; vor Uebernahme sorgfaeltig pruefen.",changes,false,true);
        }
        if (!base.getFileName().toString().equals(SimpleJson.asString(root.get("projectId")))) throw new IOException("Zwischenstand gehoert zu einem anderen Projekt.");
        List<Change> changes=new ArrayList<>();
        for(Object value:SimpleJson.asArray(root.get("changes"))) {
            Map<String,Object> row=SimpleJson.asObject(value);
            String path=SimpleJson.asString(row.get("path"));
            StoryWorkspace.resolve(base,path);
            Change change=new Change(SimpleJson.asString(row.get("action")),path,SimpleJson.asString(row.get("content")),
                    SimpleJson.asString(row.get("original")),Boolean.TRUE.equals(row.get("existed")));
            if (!StoryWorkspace.hash(change.original).equals(SimpleJson.asString(row.get("originalHash")))) throw new IOException("Zwischenstand enthaelt einen ungueltigen Originalvergleich.");
            change.applied=Boolean.TRUE.equals(row.get("applied"));
            if (row.get("writeIntent") instanceof String intent) change.writeIntent=intent;
            changes.add(change);
        }
        return new Review(base,checked,SimpleJson.asString(root.get("id")),SimpleJson.asString(root.get("createdAt")),
                SimpleJson.asString(root.get("mode")),SimpleJson.asString(root.get("instruction")),SimpleJson.asString(root.get("response")),
                SimpleJson.asString(root.get("summary")),changes,Boolean.TRUE.equals(root.get("failed")),Boolean.TRUE.equals(root.get("legacy")));
    }

    public static String json(Object value) {
        if(value==null) return "null";
        if(value instanceof Boolean || value instanceof Number) return value.toString();
        if(value instanceof Map<?,?> map) return "{"+String.join(",",map.entrySet().stream().map(entry->json(entry.getKey().toString())+":"+json(entry.getValue())).toList())+"}";
        if(value instanceof List<?> list) return "["+String.join(",",list.stream().map(StoryReviewStore::json).toList())+"]";
        StringBuilder out=new StringBuilder("\"");
        for(char c:value.toString().toCharArray()) {
            switch(c) {
                case '"' -> out.append("\\\""); case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n"); case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t");
                default -> { if(c<32) out.append(String.format("\\u%04x",(int)c)); else out.append(c); }
            }
        }
        return out.append('"').toString();
    }
}
