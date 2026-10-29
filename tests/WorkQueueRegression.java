import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.*;
import javafx.scene.control.*;
import kioberflaeche.agent.AgentJson;
import kioberflaeche.controller.WorkQueueController;
import kioberflaeche.queue.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

public class WorkQueueRegression extends AgentUiRegression {
    static Map<String,Object> row(String id, String parent, String status, int done) {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("id", id); row.put("parentId", parent); row.put("area", "Bildanalyse");
        row.put("title", parent == null ? "Bildserie mit zehn Durchlaeufen" : "Akrobatikbild - Durchlauf " + id);
        row.put("status", status); row.put("phase", status.equals("running") ? "Merkmalsanalyse" : "Wartet auf vorherigen Lauf");
        row.put("project", null); row.put("series", "Testserie"); row.put("queueScope", "serie");
        row.put("queuePosition", status.equals("queued") ? 1 : null);
        row.put("createdAt", Instant.now().minusSeconds(65).toString()); row.put("updatedAt", Instant.now().toString());
        row.put("progress", Map.of("completed", done, "total", 11, "unit", "attempted_groups"));
        row.put("failedSteps", 0);
        return row;
    }
    static String document(Instant time) {
        return AgentJson.encode(Map.of("schemaVersion","schreibai.work-monitor.v1", "generatedAt",time.toString(),
                "source", Map.of("id","series-fixture","scope","this_series_only","readOnly",true),
                "records", List.of(row("serie",null,"running",4), row("eins","serie","running",4), row("zwei","serie","queued",0))));
    }
    static void invoke(Object controller, String method) throws Exception {
        var target=controller.getClass().getDeclaredMethod(method); target.setAccessible(true); target.invoke(controller);
    }
    static void rejected(String json) {
        try { MonitorSnapshot.parse(json); throw new AssertionError("invalid snapshot accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((thread,error)->{error.printStackTrace();System.exit(1);});
        Path audit=Path.of(args[0]); Files.createDirectories(audit);
        ActivityRegistry registry=new ActivityRegistry();
        var waiting=registry.waiting("Audio","Datei 1","Stapel");
        var running=registry.start("Chat","Antwort","Ausstehend");
        check(registry.snapshot().size()==2,"waiting and running coexist");
        registry.clearFinished(); check(registry.snapshot().size()==2,"clear does not remove unfinished work");
        running.done("Antwort empfangen"); waiting.failed("Fehler");
        registry.clearFinished(); check(registry.snapshot().isEmpty(),"only terminal records clear");
        try(var threads=Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> tasks=new ArrayList<>();
            for(int i=0;i<100;i++) tasks.add(threads.submit(()->registry.start("Test","parallel","Start").done("Fertig")));
            for(var task:tasks) task.get();
        }
        check(registry.snapshot().size()==100,"concurrent transitions are retained");
        String valid=document(Instant.now());
        check(MonitorSnapshot.parse(valid).rows().size()==3,"common contract parsed");
        check(MonitorSnapshot.parse(document(Instant.now().minusSeconds(121))).stale(Instant.now()),"stale producer detected");
        rejected(valid.replace("schreibai.work-monitor.v1","unknown"));
        rejected(valid.replace("\"completed\":4","\"completed\":99"));
        rejected(valid.replace("\"id\":\"zwei\"","\"id\":\"eins\""));

        Path folder=Files.createTempDirectory(audit,"monitor-");
        Path source=Files.createDirectory(folder.resolve("series")).resolve("monitor-snapshot.json");
        Files.writeString(source,valid);
        System.setProperty("ki.queueMonitorDirectory",folder.toString());
        ActivityRegistry.shared().start("Story","Reise zwischen zwei Kuesten","Kontext wird aufgebaut");
        ActivityRegistry.shared().waiting("Transkription","Kapitel 02.wav","Wartet im Dateistapel");
        ActivityRegistry.shared().waiting("Transkription","Kapitel 03.wav","Wartet im Dateistapel");
        ActivityRegistry.shared().start("Chat","Figurenentwicklung","KI-Antwort ausstehend");
        ActivityRegistry.shared().start("Medien","Kapitel 01.mp4","Audio extrahieren").done("Audio erstellt");
        ActivityRegistry.shared().start("Test","Abgeschlossener Fehlversuch","Verarbeitung").failed("Beendet mit Fehler");
        CountDownLatch started=new CountDownLatch(1);Platform.startup(started::countDown);started.await();
        try {
            FXMLLoader loader=new FXMLLoader(WorkQueueRegression.class.getResource("/fxml/work_queue.fxml"));
            loader.setControllerFactory(type->new WorkQueueController(new kioberflaeche.ai.EphemeralTextClient("","")));
            AtomicReference<Parent> root=new AtomicReference<>();
            fx(()->{
                root.set(loader.load()); new Scene(root.get(),1100,720);
                root.get().getStylesheets().add(WorkQueueRegression.class.getResource("/css/chat.css").toExternalForm());
                ((WorkQueueController)loader.getController()).startMonitoring();
            });
            waitFor(()->((TableView<?>)field(loader,"serverTable")).getItems().size()==3);
            fx(()->{
                check(((Label)field(loader,"serverSummary")).getText().equals("1 laufend · 1 wartend"),"parents not double counted");
                check(((TableView<?>)field(loader,"localTable")).getItems().size()==4,"successes and failures hidden but pending tasks visible");
                shot(root.get(),1100,720,audit.resolve("queue-local.png"));
                ((TabPane)field(loader,"sources")).getSelectionModel().select(1);
                ((TableView<?>)field(loader,"serverTable")).getSelectionModel().select(1);
                shot(root.get(),1100,720,audit.resolve("queue-server.png"));
                shot(root.get(),640,500,audit.resolve("queue-server-small.png"));
                check(((TextArea)field(loader,"selectionDetails")).getText().contains("4/11"),"reported progress details visible");
            });
            Files.writeString(source,document(Instant.now().minusSeconds(240)));
            fx(()->invoke(loader.getController(),"refreshServer"));
            waitFor(()->((Label)field(loader,"serverSummary")).getText().contains("nicht aktuell"));
            Files.writeString(source,"{broken");
            fx(()->invoke(loader.getController(),"refreshServer"));
            waitFor(()->((Label)field(loader,"serverHealth")).getText().contains("nicht lesbar"));
            fx(()->check(((TableView<?>)field(loader,"serverTable")).getItems().size()==3,"invalid read retains last known records"));
            Files.writeString(source,document(Instant.now()));
            fx(()->invoke(loader.getController(),"refreshServer"));
            waitFor(()->((Label)field(loader,"serverSummary")).getText().equals("1 laufend · 1 wartend"));
            Files.writeString(source,document(Instant.now().minusSeconds(300))
                    .replace("\"status\":\"running\"", "\"status\":\"completed\"")
                    .replace("\"status\":\"queued\"", "\"status\":\"awaiting_review\""));
            fx(()->invoke(loader.getController(),"refreshServer"));
            waitFor(()->((Label)field(loader,"serverSummary")).getText().contains("zu pruefen"));
            fx(()->{
                check(!((Label)field(loader,"serverSummary")).getText().contains("nicht aktuell"),
                        "finished snapshots do not need endless heartbeats");
                check(((TableView<?>)field(loader,"serverTable")).getItems().isEmpty(),"settled work automatically leaves active queue");
                check(((Label)field(loader,"serverSummary")).getText().contains("Verlauf: 2 beendet"),"finished parents are not counted as jobs");
                check(((TextArea)field(loader,"selectionDetails")).getText().isEmpty(),"removed selection does not leave obsolete details");
                ((CheckBox)field(loader,"showServerFinished")).setSelected(true);
                check(((TableView<?>)field(loader,"serverTable")).getItems().size()==3,"server results remain available in history");
                check(((TableView<WorkQueueController.DisplayRow>)field(loader,"serverTable")).getItems().stream()
                        .allMatch(r->r.position().isEmpty()),"settled history does not claim a queue position");
                shot(root.get(),1100,720,audit.resolve("queue-server-history.png"));
                ((CheckBox)field(loader,"showServerFinished")).setSelected(false);
                shot(root.get(),1100,720,audit.resolve("queue-server-finished.png"));
            });
            List<Map<String,Object>> endedAndBlocked=new ArrayList<>();
            for(String status:List.of("completed","failed","cancelled","exhausted","awaiting_review","recovery_required","held"))
                endedAndBlocked.add(row(status,null,status,0));
            Files.writeString(source,AgentJson.encode(Map.of("schemaVersion","schreibai.work-monitor.v1",
                    "generatedAt",Instant.now().toString(),"source",Map.of("id","series-fixture","scope","this_series_only","readOnly",true),
                    "records",endedAndBlocked)));
            fx(()->invoke(loader.getController(),"refreshServer"));
            waitFor(()->((Label)field(loader,"serverSummary")).getText().contains("Wiederanlauf offen"));
            fx(()->{
                check(((TableView<?>)field(loader,"serverTable")).getItems().size()==2,"held and recovery remain; all five settled statuses hidden");
                check(((Label)field(loader,"serverSummary")).getText().startsWith("0 laufend · 1 wartend"),"held work counts as waiting");
                check(((Label)field(loader,"serverSummary")).getText().contains("Verlauf: 5 beendet"),"history totals include failed and review results");
                ((CheckBox)field(loader,"showServerFinished")).setSelected(true);
                check(((TableView<?>)field(loader,"serverTable")).getItems().size()==7,"every result can be restored without mutating snapshots");
                ((CheckBox)field(loader,"showServerFinished")).setSelected(false);
            });
            Files.writeString(source,document(Instant.now()));
            fx(()->invoke(loader.getController(),"refreshServer"));
            waitFor(()->((Label)field(loader,"serverSummary")).getText().equals("1 laufend · 1 wartend"));
            fx(()->{
                ((TabPane)field(loader,"sources")).getSelectionModel().select(0);
                ((CheckBox)field(loader,"showFinished")).setSelected(true);
                check(((TableView<?>)field(loader,"localTable")).getItems().size()==6,"local success and failure history can be inspected");
                invoke(loader.getController(),"clearFinished");
                check(((TableView<?>)field(loader,"localTable")).getItems().size()==4,"history clear retains active work");
            });
            Files.delete(source);
            fx(()->invoke(loader.getController(),"refreshServer"));
            waitFor(()->((Label)field(loader,"serverSummary")).getText().contains("nicht aktuell"));
            fx(()->check(((TableView<?>)field(loader,"serverTable")).getItems().size()==3,"missing source is not an empty queue"));
        } finally { Platform.exit(); }
        System.out.println("PASS: "+checks+" queue checks; synthetic files only.");
    }
}
