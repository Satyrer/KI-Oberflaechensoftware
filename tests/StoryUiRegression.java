import com.sun.net.httpserver.HttpServer;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.VBox;
import kioberflaeche.controller.StoryPaneController;
import kioberflaeche.story.*;
import kioberflaeche.util.SimpleJson;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

public class StoryUiRegression {
    static final String QUALITY_WARNING="Die Abschlussprüfung verwendet zusammengefassten Kontext. Bitte mit den Originalen vergleichen.";
    static void check(boolean test,String message) { if(!test) throw new AssertionError(message); }
    static void expectFailure(Throwing action,String name) throws Exception {
        try { action.run(); throw new AssertionError("Expected rejection: "+name); }
        catch(java.io.IOException expected) { }
    }
    interface Throwing { void run() throws Exception; }
    static void fx(Throwing action) throws Exception {
        FutureTask<Void> call=new FutureTask<>(()-> {action.run();return null;}); Platform.runLater(call); call.get(30,TimeUnit.SECONDS);
    }
    @SuppressWarnings("unchecked") static <T> T control(FXMLLoader loader,String id) { return (T)loader.getNamespace().get(id); }
    static void waitIdle(FXMLLoader loader) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<deadline) {
            AtomicReference<Boolean> idle=new AtomicReference<>(); fx(()->idle.set(!((VBox)control(loader,"projectPane")).isDisabled()));
            if(idle.get()) return; Thread.sleep(25);
        }
        throw new AssertionError("UI task did not finish");
    }
    static String response(String project,String path,String content) {
        return StoryReviewStore.json(Map.of("ok",true,"status","pending_review","projectId",project,
                "summary","Eine Szene und die vorhandenen Fakten wurden abgeglichen. Bitte den Vorschlag pruefen.",
                "file_changes",List.of(Map.of("action","upsert","path",path,"content",content)),
                "analysis",Map.of("sourceManifest",List.of("sources/story-briefing.md","sources/full-source.txt"),"completedChunks",4,"coverage","all_supplied_inputs")));
    }
    static String warningResponse(String response,String warning) {
        var root=new java.util.LinkedHashMap<>(SimpleJson.asObject(SimpleJson.parse(response)));
        root.put("warning",warning); return StoryReviewStore.json(root);
    }
    static void image(Parent root,int width,int height,Path path) throws Exception {
        root.resize(width,height); root.applyCss(); root.layout();
        WritableImage screenshot=root.snapshot(null,new WritableImage(width,height));
        BufferedImage output=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<height;y++) for(int x=0;x<width;x++) output.setRGB(x,y,screenshot.getPixelReader().getArgb(x,y));
        ImageIO.write(output,"png",path.toFile());
    }
    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((thread,error)-> { error.printStackTrace(); System.exit(1); });
        Path audit=Path.of(args[0]); Path workspace=Files.createDirectory(audit.resolve("test-workspace-"+UUID.randomUUID()));
        String briefing="Eine Forschungsreise zwischen zwei Kuesten.\n\nFiguren sollen sich widersprechen duerfen; Motive und Ereignisse nachvollziehbar erhalten.\n";
        Path alpha=StoryWorkspace.createProject(workspace,"Alpha",briefing);
        Path beta=StoryWorkspace.createProject(workspace,"Beta","Ein zweites, getrenntes Projekt.");
        String briefingOriginal=Files.readString(alpha.resolve("sources/story-briefing.md"));
        expectFailure(()->StoryWorkspace.createProject(workspace,"Alpha","replacement"),"existing project");
        check(Files.readString(alpha.resolve("sources/story-briefing.md")).equals(briefingOriginal),"existing briefing unchanged");
        expectFailure(()->StoryWorkspace.createProject(workspace,"../outside","briefing"),"project traversal");
        expectFailure(()->StoryWorkspace.resolve(alpha,"../outside.md"),"file traversal");
        check(StoryWorkspace.sceneTarget(alpha,"manuscript\\part\\scene.md").equals("manuscript/part/scene.md"),"Windows scene separator");
        for(String invalid:List.of("manuscript/a.txt","overview/story.md","manuscript/../outside.md","manuscript//a.md",
                " manuscript/a.md","manuscript/a.md ","manuscript/CON.md","manuscript/COM¹.md","manuscript/a./s.md","manuscript/imported-draft.md")) {
            expectFailure(()->StoryWorkspace.sceneTarget(alpha,invalid),"invalid scene target "+invalid);
        }
        check(!StoryWorkspace.automaticCopy("manuscript/imported-material/draft-notes.txt"),"ordinary manuscript with imported/draft words retained");
        check(StoryWorkspace.automaticCopy("manuscript/IMPORTED-DRAFT.md") && StoryWorkspace.internal(".SCHREIB-AI-backups/a.md"),"known generated copy and case-insensitive private directory excluded");
        StoryWorkspace.validateInstruction("a".repeat(1200));
        expectFailure(()->StoryWorkspace.validateInstruction("ä".repeat(601)),"UTF8 bytes rather than characters");
        expectFailure(()->StoryWorkspace.validateInstruction(StoryWorkspace.instruction("a".repeat(1200),"sources/story.md")),"source reference counted in instruction budget");
        StoryWorkspace.writeText(alpha,"manuscript/szene-001.md","Unveraenderte bestehende Szene.");
        check(StoryWorkspace.freshSceneTarget(alpha).equals("manuscript/szene-002.md"),"fresh scene avoids existing manuscript");
        Files.delete(alpha.resolve("manuscript/szene-001.md"));
        String fullSource="Anfang.\n"+"Vollstaendige Quelle mit eindeutiger Aussage.\n".repeat(2600)+"ENDE-DER-QUELLE";
        StoryWorkspace.writeText(alpha,"sources/full-source.txt",fullSource);
        StoryWorkspace.writeText(alpha,"overview/context.md","Vorhandener Kontext.");
        StoryWorkspace.writeText(alpha,"timeline/events.md","Vollstaendige Zeitfolge.");
        StoryWorkspace.writeText(alpha,"overview/source-map.md","Automatische Quellenkopie.");
        StoryWorkspace.writeText(alpha,"system/ki-synthesis-raw.md","Alter roher Modelltext.");
        var snapshot=StoryWorkspace.snapshot(alpha,Map.of("sources/story-briefing.md",briefingOriginal+"Editorzusatz."));
        check(snapshot.documents().size()==2 && snapshot.contextDocuments().size()==2 && snapshot.excludedFiles()==2,"source/context classification");
        check(snapshot.documents().stream().anyMatch(d->d.content().equals(fullSource)),"long source untruncated");
        check(snapshot.documents().stream().noneMatch(d->d.path().contains("#changed")),"no changed-only passages");
        check(snapshot.originals().get("sources/story-briefing.md").equals(briefingOriginal),"baseline distinct from editor overlay");
        System.out.println("PASS: safe new project, exact briefing, full sources/context, exclusions and immutable baseline");

        var initial=SchreibAiWorkflowClient.parseResult(warningResponse(response("Alpha","overview/context.md","Verbesserter Kontext."),QUALITY_WARNING),"Alpha");
        check(initial.warning().equals(QUALITY_WARNING),"explicit server quality warning parsed");
        var longWarning=SchreibAiWorkflowClient.parseResult(warningResponse(response("Alpha","overview/context.md","x"),"🌊".repeat(750)),"Alpha");
        check(longWarning.warning().codePointCount(0,longWarning.warning().length())==700 && longWarning.warning().endsWith("..."),"warning display bounded without broken Unicode");
        check(longWarning.raw().contains("🌊".repeat(750)),"full warning retained in raw evidence");
        for(String flag:List.of("fallback","degraded")) {
            var flagged=new java.util.LinkedHashMap<>(SimpleJson.asObject(SimpleJson.parse(response("Alpha","overview/context.md","x"))));
            flagged.put(flag,true); flagged.put("warning","   ");
            check(SchreibAiWorkflowClient.parseResult(StoryReviewStore.json(flagged),"Alpha").warning().contains("eingeschraenktes Ergebnis"),"legacy warning flag "+flag);
        }
        var review=StoryReviewStore.create(snapshot,"Kontext","Alle Fakten erhalten",initial);
        String raw=review.raw;
        review.changes.get(0).content="Von Hand gepruefter Kontext."; StoryReviewStore.save(review);
        var reopened=StoryReviewStore.open(alpha,review.file);
        check(reopened.summary.contains(QUALITY_WARNING),"quality warning persists in recovered review summary");
        check(reopened.raw.equals(raw) && reopened.changes.get(0).content.equals("Von Hand gepruefter Kontext."),"full evidence and manual edits persisted");
        Files.writeString(alpha.resolve("overview/context.md"),"Zwischenzeitliche Fremdaenderung.");
        expectFailure(()->StoryReviewStore.apply(reopened,reopened.changes.get(0)),"concurrent file change");
        check(Files.readString(alpha.resolve("overview/context.md")).equals("Zwischenzeitliche Fremdaenderung."),"conflict file preserved");
        check(Files.isRegularFile(reopened.file) && reopened.pending().size()==1,"conflict proposal preserved");
        Files.writeString(alpha.resolve("overview/context.md"),"Vorhandener Kontext.");
        StoryReviewStore.apply(reopened,reopened.changes.get(0));
        check(Files.readString(alpha.resolve("overview/context.md")).equals("Von Hand gepruefter Kontext."),"edited proposal applied");
        check(Files.readString(alpha.resolve(".schreib-ai-backups/"+reopened.id+"/overview/context.md")).equals("Vorhandener Kontext."),"targeted original backup");
        StoryReviewStore.archiveCompleted(reopened);
        check(!Files.exists(reopened.file) && Files.isRegularFile(alpha.resolve(".schreib-ai-history/"+reopened.file.getFileName())),"only completed task archived");

        var crashResult=SchreibAiWorkflowClient.parseResult(response("Alpha","manuscript/crash-scene.md","Nach Unterbrechung erhalten."),"Alpha");
        var beforeWrite=StoryReviewStore.create(StoryWorkspace.snapshot(alpha,Map.of()),"Szene","Crash vor Schreiben",crashResult);
        var beforeChange=beforeWrite.changes.get(0); beforeChange.writeIntent=beforeChange.content; StoryReviewStore.save(beforeWrite);
        var recoveredBefore=StoryReviewStore.open(alpha,beforeWrite.file);
        StoryReviewStore.apply(recoveredBefore,recoveredBefore.changes.get(0));
        check(StoryReviewStore.open(alpha,beforeWrite.file).complete(),"prepared journal resumes before file write");
        StoryReviewStore.archiveCompleted(recoveredBefore);

        var crashAfterResult=SchreibAiWorkflowClient.parseResult(response("Alpha","manuscript/crash-scene.md","Ersetzter Inhalt nach Unterbrechung."),"Alpha");
        var afterWrite=StoryReviewStore.create(StoryWorkspace.snapshot(alpha,Map.of()),"Szene","Crash nach Schreiben",crashAfterResult);
        var afterChange=afterWrite.changes.get(0); afterChange.writeIntent=afterChange.content; StoryReviewStore.save(afterWrite);
        StoryWorkspace.backup(alpha,afterChange.path,afterWrite.id); StoryWorkspace.writeText(alpha,afterChange.path,afterChange.content);
        Path pendingBackup=afterWrite.file.resolveSibling("saved-intent.bak");
        Files.move(afterWrite.file,pendingBackup); Files.createDirectory(afterWrite.file);
        var markApplied=StoryReviewStore.class.getDeclaredMethod("markApplied",StoryReviewStore.Review.class,StoryReviewStore.Change.class);
        markApplied.setAccessible(true);
        try { markApplied.invoke(null,afterWrite,afterChange); throw new AssertionError("journal save unexpectedly succeeded"); }
        catch(java.lang.reflect.InvocationTargetException expected) { check(expected.getCause() instanceof java.io.IOException,"actual journal IO error"); }
        check(!afterChange.applied && !afterWrite.complete(),"failed completion save does not mark in-memory applied");
        Files.delete(afterWrite.file); Files.move(pendingBackup,afterWrite.file);
        var recoveredAfter=StoryReviewStore.open(alpha,afterWrite.file);
        StoryReviewStore.apply(recoveredAfter,recoveredAfter.changes.get(0));
        check(StoryReviewStore.open(alpha,afterWrite.file).complete(),"prepared journal recognizes already written content without original-hash conflict");
        check(Files.readString(alpha.resolve(".schreib-ai-backups/"+afterWrite.id+"/manuscript/crash-scene.md")).equals("Nach Unterbrechung erhalten."),"retry preserves original backup");
        StoryReviewStore.archiveCompleted(recoveredAfter);
        Files.delete(alpha.resolve("manuscript/crash-scene.md"));
        System.out.println("PASS: exact scene target rules, fresh filename, UTF8 instruction budget and recoverable two-phase acceptance");

        snapshot=StoryWorkspace.snapshot(alpha,Map.of());
        var mixed=new SchreibAiWorkflowClient.Result("{\"analysis\":{\"completedChunks\":4}}","Teilpruefung",
                List.of(new SchreibAiWorkflowClient.ProposedChange("upsert","overview/context.md","Staler Vorschlag"),
                        new SchreibAiWorkflowClient.ProposedChange("upsert","manuscript/scene.md","Neue Szene.")),"");
        var mixedReview=StoryReviewStore.create(snapshot,"Szene","Pruefen",mixed);
        Files.writeString(alpha.resolve("overview/context.md"),"Erneut geaendert.");
        expectFailure(()->StoryReviewStore.apply(mixedReview,mixedReview.changes.get(0)),"partial apply conflict");
        StoryReviewStore.apply(mixedReview,mixedReview.changes.get(1)); StoryReviewStore.archiveCompleted(mixedReview);
        check(Files.exists(mixedReview.file) && mixedReview.pending().size()==1,"partial failure remains pending");
        check(Files.readString(alpha.resolve("manuscript/scene.md")).equals("Neue Szene."),"independent successful apply retained");
        var newest=StoryReviewStore.failure(alpha,"Fehlerbericht","Auftrag","{\"analysis\":{\"completedChunks\":7}}","Unterbrochen");
        Files.setLastModifiedTime(mixedReview.file,FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));
        check(StoryReviewStore.list(alpha).get(0).file().equals(newest.file),"newest task first, not mixed with older results");
        check(StoryReviewStore.open(alpha,newest.file).changes.isEmpty(),"failed partial analysis is not an acceptable proposal");
        System.out.println("PASS: journal roundtrip, source conflict, targeted backups, partial success, isolated recovery and failed analysis evidence");

        AtomicReference<Map<String,Object>> requestBody=new AtomicReference<>();
        AtomicReference<String> mockResponse=new AtomicReference<>(response("Alpha","overview/context.md","API-Vorschlag."));
        AtomicReference<CountDownLatch> entered=new AtomicReference<>();
        AtomicReference<CountDownLatch> release=new AtomicReference<>();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange-> {
            try {
                requestBody.set(SimpleJson.asObject(SimpleJson.parse(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8))));
                if(entered.get()!=null) entered.get().countDown();
                if(release.get()!=null) release.get().await(20,TimeUnit.SECONDS);
                byte[] bytes=mockResponse.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type","application/json"); exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes);
            } catch(InterruptedException error) { Thread.currentThread().interrupt(); } finally { exchange.close(); }
        });
        server.start();
        var client=new SchreibAiWorkflowClient("http://127.0.0.1:"+server.getAddress().getPort(),1800);
        var taskSnapshot=StoryWorkspace.snapshot(alpha,Map.of());
        client.buildContext("Alpha","English","Auftrag mit eigenem Schwerpunkt",taskSnapshot.documents(),taskSnapshot.contextDocuments());
        check(requestBody.get().get("instruction").equals("Auftrag mit eigenem Schwerpunkt"),"synthesis instruction");
        check(requestBody.get().get("documents").toString().contains("ENDE-DER-QUELLE"),"full source survives HTTP contract");
        check(SimpleJson.asArray(requestBody.get().get("contextDocuments")).size()==3,"complete retained context passed separately");
        client.writeScene("Alpha","Deutsch","Neue Szene","manuscript/new-scene.md",taskSnapshot.documents(),taskSnapshot.contextDocuments());
        check(requestBody.get().get("prompt").equals("Neue Szene") && requestBody.get().get("target").equals("manuscript/new-scene.md"),"scene contract");
        check(!requestBody.get().containsKey("steps") && !requestBody.get().containsKey("chapter"),"no invented steps/chapter input");
        StringBuilder controls=new StringBuilder(); for(char c=0;c<32;c++) controls.append(c);
        String controlsText=controls+" quotation: \" slash: \\ umlaut: ä emoji: 🌊";
        client.buildContext("Alpha","English","Escaping",List.of(new SchreibAiWorkflowClient.StoryDocument("sources/control.txt",controlsText)),List.of());
        check(SimpleJson.asString(SimpleJson.asObject(SimpleJson.asArray(requestBody.get().get("documents")).get(0)).get("content")).equals(controlsText),"all control characters roundtrip through HTTP JSON");
        mockResponse.set("{\"ok\":false,\"status\":\"error\",\"projectId\":\"Alpha\",\"error\":{\"message\":\"Teilauftrag unterbrochen\"},\"analysis\":{\"completedChunks\":7},\"file_changes\":[]}");
        try { client.buildContext("Alpha","English","Test",taskSnapshot.documents(),taskSnapshot.contextDocuments()); throw new AssertionError("HTTP200 error accepted"); }
        catch(SchreibAiWorkflowClient.WorkflowFailure expected) { check(expected.response().contains("completedChunks"),"typed failure retains partial evidence"); }
        expectFailure(()->SchreibAiWorkflowClient.parseResult(response("Beta","test.md","x"),"Alpha"),"project mismatch");
        expectFailure(()->SchreibAiWorkflowClient.parseResult("{\"file_changes\":[{\"path\":\"a.md\",\"content\":\"x\"},{\"path\":\"a.md\",\"content\":\"y\"}]}","Alpha"),"duplicate targets");
        System.out.println("PASS: actual HTTP contract, full long input, project identity, duplicate rejection and typed HTTP200 failures");

        Platform.startup(()->{});
        AtomicReference<FXMLLoader> loaded=new AtomicReference<>(); AtomicReference<Parent> pane=new AtomicReference<>();
        fx(()-> {
            FXMLLoader loader=new FXMLLoader(StoryUiRegression.class.getResource("/fxml/story_tab.fxml")); Parent root=loader.load();
            ((TextField)control(loader,"workspaceField")).setText(workspace.toString());
            ((StoryPaneController)loader.getController()).configure(client);
            Scene scene=new Scene(root,1280,860); scene.getStylesheets().add(StoryUiRegression.class.getResource("/css/chat.css").toExternalForm());
            loaded.set(loader); pane.set(root);
            check(((ComboBox<?>)control(loader,"languageCombo")).getValue().equals("Englisch"),"English default preserved");
            check(!((VBox)control(loader,"targetRow")).isManaged(),"target hidden for context mode");
            @SuppressWarnings("unchecked") ListView<StoryFile> files=control(loader,"sourceList");
            files.getSelectionModel().select(files.getItems().stream().filter(f->f.path().equals("sources/story-briefing.md")).findFirst().orElseThrow());
            ((TextArea)control(loader,"instructionArea")).setText("Kontext ordnen, widerspruechliche Aussagen sichtbar halten.");
        });
        FXMLLoader loader=loaded.get();
        mockResponse.set(warningResponse(response("Alpha","overview/context.md","UI-Vorschlag: Alle belegten Motive bleiben sichtbar."),QUALITY_WARNING));
        entered.set(new CountDownLatch(1)); release.set(new CountDownLatch(1));
        fx(()->((Button)control(loader,"runTaskButton")).fire());
        check(entered.get().await(10,TimeUnit.SECONDS),"UI request reached server");
        fx(()-> { check(((VBox)control(loader,"projectPane")).isDisabled() && ((VBox)control(loader,"taskPane")).isDisabled(),"project and task inputs locked while running"); image(pane.get(),1280,860,audit.resolve("story-ui-running-1280.png")); });
        release.get().countDown(); waitIdle(loader); entered.set(null);release.set(null);
        fx(()-> {
            check(((Label)control(loader,"resultLabel")).getText().contains(QUALITY_WARNING),"quality warning visible in review");
            ((Button)control(loader,"recoverTaskButton")).fire();
            check(((Label)control(loader,"resultLabel")).getText().contains(QUALITY_WARNING),"quality warning visible after real UI recovery");
            check(((Label)control(loader,"sourcePathLabel")).getText().equals("sources/story-briefing.md"),"proposal did not replace editor path");
            check(((TextArea)control(loader,"sourceEditor")).getText().equals(briefingOriginal),"proposal did not replace source text");
            check(((ListView<?>)control(loader,"proposalList")).getItems().size()==1,"visible proposal list");
            ((TextArea)control(loader,"proposedArea")).setText("UI-gepruefter Kontext.");
            image(pane.get(),1280,860,audit.resolve("story-ui-1280.png"));
            image(pane.get(),920,530,audit.resolve("story-ui-920.png"));
            for(String id:List.of("recoverTaskButton","refreshProjectsButton")) {
                Button button=control(loader,id); check(button.getWidth()+.5>=button.prefWidth(-1),"button label not clipped at 920px: "+id);
            }
            image(pane.get(),620,530,audit.resolve("story-ui-620.png"));
            for(String id:List.of("recoverTaskButton","refreshProjectsButton")) {
                Button button=control(loader,id); check(button.getWidth()+.5>=button.prefWidth(-1),"button label not clipped at 620px story area: "+id);
            }
            ((Button)control(loader,"applyOneButton")).fire();
            check(Files.readString(alpha.resolve("overview/context.md")).equals("UI-gepruefter Kontext."),"explicit edited proposal applied");
            check(Files.readString(alpha.resolve("sources/story-briefing.md")).equals(briefingOriginal),"source untouched by proposal selection/apply");
        });
        mockResponse.set("{\"ok\":false,\"status\":\"error\",\"projectId\":\"Alpha\",\"error\":{\"message\":\"Test-Unterbrechung\"},\"analysis\":{\"completedChunks\":9},\"file_changes\":[]}");
        fx(()->((Button)control(loader,"runTaskButton")).fire()); waitIdle(loader);
        fx(()-> {
            check(((Button)control(loader,"applyAllButton")).isDisabled(),"failed task cannot be accepted");
            check(((TextArea)control(loader,"detailsArea")).getText().contains("completedChunks"),"failed analysis retained in details");
            var saved=StoryReviewStore.list(alpha).get(0); var failure=StoryReviewStore.open(alpha,saved.file());
            check(failure.failed && failure.raw.contains("completedChunks"),"failed analysis persisted on disk");
            ((ComboBox<StoryPaneController.Mode>)control(loader,"modeCombo")).setValue(StoryPaneController.Mode.WRITE);
            check(((VBox)control(loader,"targetRow")).isManaged(),"scene mode exposes labelled target");
            check(((TextField)control(loader,"targetPathField")).getText().equals("manuscript/szene-001.md"),"UI fresh target default");
            ((TextField)control(loader,"targetPathField")).setText("manuscript/scene.md");
            check(((Label)control(loader,"targetHintLabel")).getText().contains("gesamten Inhalt"),"existing target replacement warning");
            var beforeRequest=requestBody.get();
            ((TextArea)control(loader,"instructionArea")).setText("a".repeat(1200));
            ((Button)control(loader,"runTaskButton")).fire();
            check(requestBody.get()==beforeRequest && !((VBox)control(loader,"projectPane")).isDisabled(),"overbudget UI task rejected before request");
            check(((Label)control(loader,"statusLabel")).getText().contains("einschliesslich Dateiverweis"),"UI budget error explains source reference");
            ((TextArea)control(loader,"instructionArea")).setText("Die naechste Szene aus Sicht der Forscherin schreiben. Bestehende Fakten erhalten.");
            ((TextField)control(loader,"targetPathField")).setText("manuscript/szene-001.md");
        });
        mockResponse.set(response("Alpha","manuscript/szene-001.md","Am anderen Ufer bemerkte die Forscherin das schwache Licht im alten Bootshaus. Sie hielt an und verglich es mit ihrer letzten Notiz."));
        fx(()->((Button)control(loader,"runTaskButton")).fire()); waitIdle(loader);
        fx(()-> {
            check(requestBody.get().get("target").equals("manuscript/szene-001.md"),"real UI scene target payload");
            check(((ListView<?>)control(loader,"proposalList")).getItems().size()==1,"scene proposal ready for manual review");
            image(pane.get(),1280,860,audit.resolve("story-ui-scene-1280.png"));
            image(pane.get(),920,530,audit.resolve("story-ui-scene-920.png"));
            var columns=(SplitPane)pane.get().lookup(".content-split");
            ((ScrollPane)columns.getItems().get(1)).setVvalue(1);
            image(pane.get(),1280,860,audit.resolve("story-ui-review-1280.png"));
            ((TextField)control(loader,"projectNameField")).setText("Meine neue Story");
            ((TextArea)control(loader,"briefingArea")).setText("Eine Stadt an zwei Kuesten. Zwei Figuren beurteilen dasselbe Ereignis unterschiedlich.\n\nDas Briefing haelt Motive, Welt, offene Fragen und besondere Anforderungen als Quelle fest.");
            for(var node:pane.get().lookupAll(".titled-pane")) if(node instanceof TitledPane titled && titled.getText().equals("Neues Projekt anlegen")) titled.setExpanded(true);
            ((ScrollPane)columns.getItems().get(0)).setVvalue(0);
            image(pane.get(),1280,860,audit.resolve("story-ui-new-project-1280.png"));
        });
        System.out.println("PASS: real FXML/controller, locked task inputs, independent source/proposal editors, manual acceptance and persisted failure evidence");
        System.out.println("PASS: explicit bounded server warning, legacy warning flags and visible quality warning after journal/UI recovery");
        System.out.println("PASS: PNG snapshots at ordinary and minimum window size; raw details collapsed by default");
        server.stop(0); Platform.exit(); System.out.println("ALL STORY UI REGRESSIONS PASSED");
    }
}
