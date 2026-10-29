import kioberflaeche.story.SchreibAiWorkflowClient;
import kioberflaeche.story.StoryReviewStore;
import kioberflaeche.story.StoryWorkspace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Isolated fixtures only; no network, real work projects or production UI. */
public final class DocumentSourceIsolationRegression {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private interface Throwing { void run() throws Exception; }
    private static void rejected(Throwing action, String message) throws Exception {
        try { action.run(); throw new AssertionError("Expected rejection: " + message); }
        catch (IOException expected) { }
    }
    private static void fixture(Path project, String relative, String content) throws IOException {
        Path target = project.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }
    private static SchreibAiWorkflowClient.Result proposal(String path) {
        return new SchreibAiWorkflowClient.Result("{}", "Testvorschlag",
                List.of(new SchreibAiWorkflowClient.ProposedChange("upsert", path, "Nicht uebernehmen")), "");
    }
    public static void main(String[] args) throws Exception {
        Path audit = Files.createDirectories(Path.of(args[0]));
        Path workspace = Files.createDirectory(audit.resolve("document-isolation-" + UUID.randomUUID()));
        Path project = StoryWorkspace.createProject(workspace, "Testprojekt", "Bestaetigtes Briefing");
        for (String path : List.of("documents", "documents/raw/text.md", "DOCUMENTS\\v1\\text.txt",
                "./documents/current.md", "sources/../documents/current.md", "sources\\..\\documents\\current.md")) {
            check(StoryWorkspace.internal(path), "Reserved normalized path: " + path);
            check(StoryWorkspace.automaticCopy(path), "Not classified as fresh story input: " + path);
        }
        for (String path : List.of("sources/documents/notes.md", "documents-notes.md", "sources/documents.txt")) {
            check(!StoryWorkspace.internal(path), "Ordinary source retained: " + path);
        }
        fixture(project, "documents/doc-a/raw/extracted.txt", "UNGEPRUEFTER-ROHTEXT");
        fixture(project, "documents/doc-a/versions/1/work.md", "ALTE-VERSION");
        fixture(project, "documents/doc-a/versions/2/work.md", "AKTUELLE-VERSION-OHNE-ADAPTER");
        fixture(project, "documents/doc-a/review/instructions.md", "VERDACHT-ANWEISUNG");
        // Invalid UTF-8 would make snapshot fail if it attempted to read the reserved tree.
        Files.write(project.resolve("documents/unreadable.txt"), new byte[] {(byte) 0xc3, (byte) 0x28});
        fixture(project, ".schreib-ai-temp/private.md", "INTERNER-TEXT");
        fixture(project, "sources/documents/notes.md", "Gewoehnliche Quellenuntermappe");
        fixture(project, "documents-notes.md", "Gewoehnliche Quelldatei");
        fixture(project, "overview/context.md", "Bestaetigter Storykontext");
        fixture(project, "overview/source-map.md", "Automatische Kopie");

        var listed = StoryWorkspace.files(project);
        check(listed.size() == 5, "Only ordinary text files in the legacy UI");
        check(listed.stream().noneMatch(file -> StoryWorkspace.internal(file.path())), "No document or private entries in list");
        var snapshot = StoryWorkspace.snapshot(project, Map.of(
                "documents/doc-a/raw/extracted.txt", "DRAFT-UMGEHUNG",
                "documents/new.md", "ZUSAETZLICHER-DRAFT",
                "sources/story-briefing.md", "Expliziter Briefingentwurf"));
        check(snapshot.documents().size() == 3 && snapshot.contextDocuments().size() == 1,
                "Legacy source and context counts unchanged");
        check(snapshot.excludedFiles() == 1, "Ordinary generated-copy accounting unchanged");
        check(snapshot.originals().size() == 5, "Raw and versioned content absent even from immutable originals");
        check(snapshot.documents().stream().anyMatch(doc -> doc.content().equals("Expliziter Briefingentwurf")),
                "Ordinary editor overlay retained");
        check(snapshot.documents().stream().noneMatch(doc -> doc.content().contains("DRAFT-UMGEHUNG")),
                "Saved editor drafts cannot inject document contents");
        System.out.println("PASS: entire documents tree absent from file list, source/context snapshots and originals; draft bypass rejected");

        for (String path : List.of("documents/new.md", "DOCUMENTS\\new.md", "sources/../documents/new.md",
                project.resolve("documents/new.md").toString())) {
            rejected(() -> StoryWorkspace.writeText(project, path, "Ueberschreiben"), "Story write " + path);
        }
        rejected(() -> StoryReviewStore.create(snapshot, "Kontext", "Test", proposal("documents/new.md")), "New model proposal");
        rejected(() -> StoryReviewStore.create(snapshot, "Kontext", "Test", proposal("sources/../documents/new.md")), "Normalized model proposal");
        check(!Files.exists(project.resolve("documents/new.md")), "Rejected writes never create a document");

        // A pending proposal from an older app must also fail at the final write boundary.
        var oldReview = StoryReviewStore.failure(project, "Alter Auftrag", "Test", "{}", "Fixture");
        var oldChange = new StoryReviewStore.Change("upsert", "documents/new.md", "Alter Vorschlag", "", false);
        var recovered = new StoryReviewStore.Review(project, oldReview.file, oldReview.id, oldReview.createdAt,
                "Kontext", "Test", "{}", "Vorher gespeicherter Vorschlag", List.of(oldChange), false, true);
        rejected(() -> StoryReviewStore.apply(recovered, oldChange), "Recovered old review");
        check(!Files.exists(project.resolve("documents/new.md")) && !oldChange.applied, "Old review cannot write reserved data");
        System.out.println("PASS: normalized, absolute and recovered story writes cannot alter the document store");

        Path alias = project.resolve("sources/document-alias.md");
        boolean symlinksAvailable = true;
        try { Files.createSymbolicLink(alias, project.resolve("documents/doc-a/versions/1/work.md")); }
        catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            symlinksAvailable = false;
            System.out.println("SKIP: symbolic-link fixture unavailable on this Windows account");
        }
        if (symlinksAvailable) {
            check(StoryWorkspace.files(project).stream().noneMatch(file -> file.path().equals("sources/document-alias.md")),
                    "File symbolic link cannot expose reserved content");
            rejected(() -> StoryWorkspace.writeText(project, "sources/document-alias.md", "Alias-Schreibversuch"), "File symbolic-link write");
            Path directoryAlias = project.resolve("document-directory-alias");
            Files.createSymbolicLink(directoryAlias, project.resolve("documents"));
            rejected(() -> StoryWorkspace.writeText(project, "document-directory-alias/new.md", "Alias-Schreibversuch"), "Directory symbolic-link write");
            check(Files.readString(project.resolve("documents/doc-a/versions/1/work.md")).equals("ALTE-VERSION"),
                    "Aliased original unchanged");
            System.out.println("PASS: symbolic file and directory aliases cannot bypass the reserved area");
        }
        System.out.println("ALL DOCUMENT SOURCE ISOLATION REGRESSIONS PASSED");
    }
}
