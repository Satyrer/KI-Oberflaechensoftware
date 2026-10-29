import com.sun.net.httpserver.HttpServer;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.stage.Stage;
import javafx.stage.Modality;
import javafx.scene.image.WritableImage;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import kioberflaeche.ai.EphemeralTextClient;
import kioberflaeche.controller.WorkQueueController;
import kioberflaeche.ui.EphemeralTextDialog;
import kioberflaeche.queue.ActivityRegistry;
import kioberflaeche.queue.JobNumbers;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class EphemeralQueueRegression extends EphemeralUiRegression {
    static final String ROW="{\"id\":\"0123456789abcdef0123456789abcdef\",\"context\":\"Dolphinlauf\",\"status\":\"running\",\"created_at\":1793180000}";
    static String document(String rows) { return "{\"ok\":true,\"runs\":["+rows+"],\"privacy\":{\"mode\":\"memory_only\",\"applicationContentPersisted\":false},\"scope\":\"ephemeral_metadata_only\",\"restartResetsHistory\":true}"; }
    static <T> T field(FXMLLoader loader,String name) { return (T)loader.getNamespace().get(name); }
    interface Condition { boolean get() throws Exception; }
    static void waitFor(Condition condition) throws Exception {
        AtomicBoolean ready=new AtomicBoolean();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<until) { fx(()->ready.set(condition.get()));if(ready.get())return;Thread.sleep(25); }
        throw new AssertionError("metadata UI did not settle");
    }
    static void refresh(FXMLLoader loader) throws Exception { fx(()->EphemeralQueueRegression.<Button>field(loader,"refreshServerButton").fire()); }
    public static void main(String[] args) throws Exception {
        Path audit=Path.of(args[0]).toAbsolutePath();Files.createDirectories(audit);
        Path fixture=Files.createTempDirectory(audit,"ephemeral-queue-");Path token=fixture.resolve("test.token");
        Files.writeString(token,"test-only-credential-012345678901234567890123456789");
        System.setProperty("ki.queueMonitorDirectory",fixture.toString());
        AtomicReference<String> response=new AtomicReference<>(document(ROW));AtomicInteger reads=new AtomicInteger(),redirects=new AtomicInteger();AtomicBoolean redirect=new AtomicBoolean(),stall=new AtomicBoolean();
        CountDownLatch releaseStall=new CountDownLatch(1),generationStarted=new CountDownLatch(1),releaseGeneration=new CountDownLatch(1);
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/api/ephemeral-runs",exchange->{try {
            reads.incrementAndGet();check(exchange.getRequestMethod().equals("GET"),"metadata GET only");
            check(exchange.getRequestURI().getRawQuery()==null && exchange.getRequestBody().readAllBytes().length==0,"no metadata query/body");
            check(exchange.getRequestHeaders().getFirst("Authorization").equals("Bearer "+Files.readString(token)),"dedicated metadata credential");
            if(stall.get()) {exchange.sendResponseHeaders(200,500);exchange.getResponseBody().write('{');exchange.getResponseBody().flush();try {releaseStall.await(30,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}return;}
            if(redirect.get()) {exchange.getResponseHeaders().add("Location","/redirect");exchange.sendResponseHeaders(302,-1);return;}
            byte[] bytes=response.get().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        } finally {exchange.close();}});
        server.createContext("/redirect",exchange->{redirects.incrementAndGet();exchange.sendResponseHeaders(204,-1);exchange.close();});
        server.createContext("/api/ephemeral-text",exchange->{try {
            exchange.getRequestBody().readAllBytes();generationStarted.countDown();
            try {releaseGeneration.await(20,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}
            byte[] bytes=GOOD.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        } finally {exchange.close();}});
        server.start();String base="http://127.0.0.1:"+server.getAddress().getPort();
        var client=new EphemeralTextClient(base+"/api/ephemeral-text",token.toString());
        AtomicBoolean fxStarted=new AtomicBoolean();
        try {
            check(client.readRuns().size()==1,"metadata accepted");
            for(String bad:List.of(document(ROW).replace("ephemeral_metadata_only","other"),document(ROW).replace("\"restartResetsHistory\":true","\"restartResetsHistory\":false"),document(ROW).replace("Dolphinlauf","SERVER-SECRET"),document(ROW.replace("\"status\"","\"prompt\":\"SERVER-SECRET\",\"status\"")),document(ROW+","+ROW))) {
                response.set(bad);rejects(client::readRuns,"untrusted metadata rejected");
            }
            redirect.set(true);rejects(client::readRuns,"metadata redirect refused");check(redirects.get()==0,"no credential redirect");redirect.set(false);response.set(document(ROW));
            response.set("x".repeat(65537));rejects(client::readRuns,"metadata body limit");response.set(document(ROW));
            stall.set(true);long beforeStall=System.nanoTime();rejects(client::readRuns,"stalled metadata body times out");
            check(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-beforeStall)<13,"full body timeout includes stalled stream after headers");stall.set(false);releaseStall.countDown();
            check(client.readRuns().size()==1,"client recovers after stalled body");
            ActivityRegistry registry=new ActivityRegistry();registry.start("Test","Normal before","Verarbeitung");
            long normalBefore=JobNumbers.forId("local:"+registry.snapshot().getFirst().id());
            Platform.startup(()->{});fxStarted.set(true);Platform.setImplicitExit(false);
            FXMLLoader loader=new FXMLLoader(EphemeralQueueRegression.class.getResource("/fxml/work_queue.fxml"));
            AtomicReference<Stage> stage=new AtomicReference<>();
            fx(()-> {
                loader.setControllerFactory(type->new WorkQueueController(client));Parent root=loader.load();
                root.getStylesheets().add(EphemeralQueueRegression.class.getResource("/css/chat.css").toExternalForm());
                Stage window=new Stage();window.setScene(new Scene(root,1100,720));stage.set(window);window.show();
                EphemeralQueueRegression.<TabPane>field(loader,"sources").getSelectionModel().select(1);
                ((WorkQueueController)loader.getController()).startMonitoring();
            });
            waitFor(()->EphemeralQueueRegression.<TableView<?>>field(loader,"serverTable").getItems().size()==1);
            fx(()-> {
                TableView<WorkQueueController.DisplayRow> table=field(loader,"serverTable");var row=table.getItems().getFirst();
                check(row.number()==normalBefore+1 && row.title().equals("Auftrag "+row.number()) && row.area().equals("Dolphinlauf"),"generic title/context and shared normal/private sequence");
                check(row.status().equals("In Bearbeitung") && row.tone().equals("running"),"existing running name/color");
                table.getSelectionModel().selectFirst();String detail=EphemeralQueueRegression.<TextArea>field(loader,"selectionDetails").getText();
                check(detail.equals(row.title()+"\nKontext: Dolphinlauf\nStatus: In Bearbeitung"),"selection only number/context/status");
            });
            ActivityRegistry after=new ActivityRegistry();after.start("Test","Normal after","Verarbeitung");
            check(JobNumbers.forId("local:"+after.snapshot().getFirst().id())==normalBefore+2,"normal/private/normal use one counter");
            FXMLLoader second=new FXMLLoader(EphemeralQueueRegression.class.getResource("/fxml/work_queue.fxml"));AtomicReference<Stage> secondStage=new AtomicReference<>();
            fx(()-> { second.setControllerFactory(type->new WorkQueueController(client));Stage window=new Stage();window.setScene(new Scene(second.load(),1100,720));secondStage.set(window);window.show();((WorkQueueController)second.getController()).startMonitoring(); });
            waitFor(()->EphemeralQueueRegression.<TableView<?>>field(second,"serverTable").getItems().size()==1);
            fx(()-> {check(EphemeralQueueRegression.<TableView<WorkQueueController.DisplayRow>>field(second,"serverTable").getItems().getFirst().number()==normalBefore+1,"independent controllers share stable number");secondStage.get().close();});
            AtomicReference<Stage> privateWindow=new AtomicReference<>();
            fx(()-> {
                privateWindow.set(EphemeralTextDialog.open(stage.get(),client));
                check(privateWindow.get().getOwner()==stage.get() && privateWindow.get().getModality()==Modality.NONE,"owned private dialog leaves queue accessible");
                ((TextArea)privateWindow.get().getScene().lookup("#ephemeralInput")).setText("EPHEMERAL-QUEUE-PROMPT");
                ((Button)privateWindow.get().getScene().lookup("#ephemeralSend")).fire();
            });
            check(generationStarted.await(5,TimeUnit.SECONDS),"private generation waiting");
            fx(()-> {
                check(!stage.get().getScene().getRoot().isDisabled(),"main queue remains enabled during private generation");
                EphemeralQueueRegression.<CheckBox>field(loader,"showServerFinished").setSelected(true);
                EphemeralQueueRegression.<CheckBox>field(loader,"showServerFinished").setSelected(false);
                var scene=stage.get().getScene();scene.getRoot().applyCss();scene.getRoot().layout();
                WritableImage image=scene.snapshot(null);BufferedImage png=new BufferedImage((int)image.getWidth(),(int)image.getHeight(),BufferedImage.TYPE_INT_ARGB);
                for(int y=0;y<png.getHeight();y++)for(int x=0;x<png.getWidth();x++)png.setRGB(x,y,image.getPixelReader().getArgb(x,y));
                ImageIO.write(png,"png",audit.resolve("ephemeral-queue.png").toFile());
            });
            releaseGeneration.countDown();waitFor(()->((TextArea)privateWindow.get().getScene().lookup("#ephemeralOutput")).getText().equals("MEMORY-ONLY-ANSWER"));
            fx(()->privateWindow.get().close());
            for(String status:List.of("completed","failed")) {
                fx(()->EphemeralQueueRegression.<CheckBox>field(loader,"showServerFinished").setSelected(true));
                response.set(document(ROW.replace("running",status)));refresh(loader);
                waitFor(()->EphemeralQueueRegression.<TableView<WorkQueueController.DisplayRow>>field(loader,"serverTable").getItems().getFirst().status().equals(status.equals("completed")?"Abgeschlossen":"Fehlgeschlagen"));
                fx(()-> {
                    var row=EphemeralQueueRegression.<TableView<WorkQueueController.DisplayRow>>field(loader,"serverTable").getItems().getFirst();
                    check(row.status().equals(status.equals("completed")?"Abgeschlossen":"Fehlgeschlagen"),"existing terminal names");
                    check(row.tone().equals(status.equals("completed")?"done":"failed"),"existing terminal colors");
                    check(row.number()==normalBefore+1,"refresh and history keep same reference");
                    EphemeralQueueRegression.<CheckBox>field(loader,"showServerFinished").setSelected(false);
                    check(EphemeralQueueRegression.<TableView<?>>field(loader,"serverTable").getItems().isEmpty(),"terminal hidden from active queue");
                });
            }
            response.set(document(ROW));refresh(loader);waitFor(()->EphemeralQueueRegression.<TableView<?>>field(loader,"serverTable").getItems().size()==1);
            response.set("{\"error\":\"SERVER-SECRET\"}");refresh(loader);
            waitFor(()->EphemeralQueueRegression.<Label>field(loader,"serverHealth").getText().contains("Dolphin-Status derzeit"));
            fx(()->check(EphemeralQueueRegression.<TableView<WorkQueueController.DisplayRow>>field(loader,"serverTable").getItems().getFirst().status().equals("Stand veraltet"),"unavailable metadata marked stale"));
            response.set(document(""));refresh(loader);waitFor(()->EphemeralQueueRegression.<TableView<?>>field(loader,"serverTable").getItems().isEmpty());
            fx(()->check(EphemeralQueueRegression.<TextArea>field(loader,"selectionDetails").getText().isEmpty(),"server reset removes previous details"));
            fx(()->stage.get().hide());int before=reads.get();refresh(loader);waitFor(()->!EphemeralQueueRegression.<Button>field(loader,"refreshServerButton").isDisabled());
            check(reads.get()==before,"hidden queue does not poll private route");
            try(var paths=Files.walk(fixture)) { check(paths.filter(Files::isRegularFile).count()==1,"metadata creates no files"); }
            Platform.exit();System.out.println("PASS: metadata GET/auth/validation, shared queue names/colors/history/reset, generic details, visible-only polling, no content files");
        } finally {releaseStall.countDown();releaseGeneration.countDown();if(fxStarted.get())Platform.exit();server.stop(0);}
    }
}
