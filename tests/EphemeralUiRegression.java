import com.sun.net.httpserver.HttpServer;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.scene.image.WritableImage;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import kioberflaeche.ai.*;
import kioberflaeche.admin.N8nWebChatClient;
import kioberflaeche.controller.ChatController;
import kioberflaeche.controller.StoryPaneController;
import kioberflaeche.storage.ChatStore;
import kioberflaeche.story.SchreibAiWorkflowClient;
import kioberflaeche.ui.EphemeralTextDialog;
import kioberflaeche.util.SimpleJson;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class EphemeralUiRegression {
    public static class IsolatedStory extends StoryPaneController {
        @Override public void configure(SchreibAiWorkflowClient client) { }
    }
    static void check(boolean value,String name) { if(!value)throw new AssertionError(name); }
    interface Action { void run() throws Exception; }
    static void fx(Action action) throws Exception { FutureTask<Void> task=new FutureTask<>(()->{action.run();return null;});Platform.runLater(task);task.get(20,TimeUnit.SECONDS); }
    static void rejects(Action action,String name) throws Exception { try {action.run();throw new AssertionError(name);}catch(java.io.IOException expected){check(!expected.getMessage().contains("SERVER-SECRET"),"generic exception");} }
    static final String GOOD="{\"ok\":true,\"response\":\"MEMORY-ONLY-ANSWER\",\"modelRouting\":{\"selected\":\"dolphin3-8b\",\"model\":\"dolphin3:8b\",\"task\":\"story_write\"},\"cleanup\":{\"verified\":true},\"privacy\":{\"mode\":\"memory_only\",\"applicationContentPersisted\":false}}";
    public static void main(String[] args) throws Exception {
        Path audit=Path.of(args[0]).toAbsolutePath();Files.createDirectories(audit);
        Path fixture=Files.createTempDirectory(audit,"ephemeral-fixture-");Path token=fixture.resolve("test.token");
        Files.writeString(token,"test-only-credential-012345678901234567890123456789");
        AtomicReference<String> answer=new AtomicReference<>(GOOD);AtomicInteger requests=new AtomicInteger(),redirectHits=new AtomicInteger();
        AtomicReference<Map<String,Object>> body=new AtomicReference<>();AtomicBoolean redirect=new AtomicBoolean();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/ephemeral-text",exchange->{
            try {
                requests.incrementAndGet();
                check(exchange.getRequestHeaders().getFirst("Authorization").equals("Bearer "+Files.readString(token)),"dedicated token");
                body.set(SimpleJson.asObject(SimpleJson.parse(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8))));
                if(redirect.get()) { exchange.getResponseHeaders().add("Location","/secret-sink");exchange.sendResponseHeaders(302,-1);return; }
                byte[] raw=answer.get().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,raw.length);exchange.getResponseBody().write(raw);
            } finally {exchange.close();}
        });
        server.createContext("/secret-sink",exchange->{redirectHits.incrementAndGet();exchange.sendResponseHeaders(204,-1);exchange.close();});
        server.createContext("/webhook/schreib-ai/models",exchange->{
            byte[] raw="{\"ok\":true,\"models\":[{\"id\":\"dolphin3-8b\",\"label\":\"Dolphin 3 8B\",\"available\":true,\"tasks\":[\"story_write\",\"story_context\"]}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,raw.length);exchange.getResponseBody().write(raw);exchange.close();
        });
        server.start();String base="http://127.0.0.1:"+server.getAddress().getPort();
        var client=new EphemeralTextClient(base+"/api/ephemeral-text",token.toString());
        try {
            var result=client.generate("MEMORY-ONLY-PROMPT \"quotes\"\nline","Deutsch");
            check(result.text().equals("MEMORY-ONLY-ANSWER"),"response parsed");
            check(Boolean.TRUE.equals(body.get().get("memory_only")),"memory_only mandatory");
            check(body.get().get("modelSelection").equals("dolphin3-8b") && body.get().get("task").equals("story_write"),"explicit model/task");
            check(body.get().keySet().equals(Set.of("memory_only","modelSelection","task","prompt","system","numPredict","timeout_seconds")),"no project/history/files fields");
            check(body.get().get("prompt").equals("MEMORY-ONLY-PROMPT \"quotes\"\nline"),"complete prompt escaping");
            rejects(()->client.generate("x".repeat(9000),"Deutsch"),"long prompt rejection");
            check(requests.get()==1,"oversize not transmitted");
            for(String bad:List.of(GOOD.replace("dolphin3-8b","other-model"),GOOD.replace("\"verified\":true","\"verified\":false"),GOOD.replace("\"applicationContentPersisted\":false","\"applicationContentPersisted\":true"),"{\"error\":\"SERVER-SECRET\"}")) {
                answer.set(bad);rejects(()->client.generate("MEMORY-ONLY-PROMPT","Deutsch"),"unverified answer rejected");
            }
            redirect.set(true);rejects(()->client.generate("MEMORY-ONLY-PROMPT","Deutsch"),"redirect rejected");check(redirectHits.get()==0,"no redirected credential or content");redirect.set(false);answer.set(GOOD);
            rejects(()->new EphemeralTextClient("https://example.com/api/ephemeral-text",token.toString()).generate("x","Deutsch"),"public destination rejected");
            System.out.println("PASS: dedicated route, bounded complete input, exact privacy/model/cleanup checks, no redirect or saved fallback");

            System.setProperty("ki.ephemeral.endpoint",base+"/api/ephemeral-text");System.setProperty("ki.ephemeral.tokenFile",token.toString());
            Platform.startup(()->{});Platform.setImplicitExit(false);
            AtomicReference<Stage> dialog=new AtomicReference<>();AtomicReference<FXMLLoader> loaderRef=new AtomicReference<>();
            fx(()-> {
                FXMLLoader loader=new FXMLLoader(EphemeralUiRegression.class.getResource("/fxml/story_tab.fxml"));Parent root=loader.load();new Scene(root);
                ((TextField)loader.getNamespace().get("workspaceField")).setText(fixture.toString());
                ((StoryPaneController)loader.getController()).configure(new SchreibAiWorkflowClient(base,30));loaderRef.set(loader);
                ((TextArea)loader.getNamespace().get("instructionArea")).setText("SAVED-FIELD-MUST-NOT-COPY");
                dialog.set(EphemeralTextDialog.open(null,client));
                check(((TextArea)dialog.get().getScene().lookup("#ephemeralInput")).getText().isEmpty(),"dialog starts empty");
                var scene=dialog.get().getScene();scene.getRoot().applyCss();scene.getRoot().layout();
                WritableImage image=scene.snapshot(null);BufferedImage png=new BufferedImage((int)image.getWidth(),(int)image.getHeight(),BufferedImage.TYPE_INT_ARGB);
                for(int y=0;y<png.getHeight();y++)for(int x=0;x<png.getWidth();x++)png.setRGB(x,y,image.getPixelReader().getArgb(x,y));
                ImageIO.write(png,"png",audit.resolve("ephemeral-empty-dialog.png").toFile());
                ((TextArea)dialog.get().getScene().lookup("#ephemeralInput")).setText("MEMORY-ONLY-PROMPT");
                ((Button)dialog.get().getScene().lookup("#ephemeralSend")).fire();
            });
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);AtomicBoolean finished=new AtomicBoolean();
            while(System.nanoTime()<deadline) {
                fx(()->finished.set(((TextArea)dialog.get().getScene().lookup("#ephemeralOutput")).getText().equals("MEMORY-ONLY-ANSWER")));
                if(finished.get())break;Thread.sleep(30);
            }
            check(finished.get(),"dialog displays verified answer");
            fx(()-> {
                var input=(TextArea)dialog.get().getScene().lookup("#ephemeralInput");var output=(TextArea)dialog.get().getScene().lookup("#ephemeralOutput");
                dialog.get().close();check(input.getText().isEmpty() && output.getText().isEmpty(),"closing clears view");
                FXMLLoader loader=loaderRef.get();
                @SuppressWarnings("unchecked") ComboBox<ModelCatalogClient.Option> model=(ComboBox<ModelCatalogClient.Option>)loader.getNamespace().get("storyModelCombo");
                model.setValue(new ModelCatalogClient.Option("dolphin3-8b","Dolphin",true,List.of("story_write","story_context"),""));
                CheckBox mode=(CheckBox)loader.getNamespace().get("memoryOnlyCheck");
                check(mode.isSelected(),"Dolphin defaults to memory-only");
                check(((javafx.scene.layout.VBox)loader.getNamespace().get("savedTaskInputs")).isDisabled(),"saved inputs disabled in memory-only mode");
                mode.setSelected(false);check(((Label)loader.getNamespace().get("storageModeLabel")).getText().startsWith("Mit Speicherung"),"saved mode explicitly labeled");
            });
            AtomicInteger savedCalls=new AtomicInteger();AtomicReference<FXMLLoader> chatLoader=new AtomicReference<>();
            Path chats=fixture.resolve("chats");System.setProperty("ki.queueMonitorDirectory",fixture.toString());
            fx(()-> {
                FXMLLoader loader=new FXMLLoader(EphemeralUiRegression.class.getResource("/fxml/chat_window.fxml"));
                loader.setControllerFactory(type->{try {
                    if(type==ChatController.class) return new ChatController((id,history,prompt)-> {savedCalls.incrementAndGet();return CompletableFuture.completedFuture("SAVED-ROUTE-MUST-NOT-RUN");},new ChatStore(chats),new N8nWebChatClient("","","",30),new SchreibAiWorkflowClient(base,30));
                    if(type==StoryPaneController.class)return new IsolatedStory();
                    return type.getDeclaredConstructor().newInstance();
                } catch(Exception error){throw new RuntimeException(error);}});
                Parent root=loader.load();new Scene(root);chatLoader.set(loader);
                ((TextArea)loader.getNamespace().get("inputArea")).setText("CHAT-FIELD-MUST-NOT-COPY");
                @SuppressWarnings("unchecked") ComboBox<ModelCatalogClient.Option> models=(ComboBox<ModelCatalogClient.Option>)loader.getNamespace().get("chatModelCombo");
                models.setValue(new ModelCatalogClient.Option("dolphin3-8b","Dolphin",true,List.of("chat"),""));
                check(((CheckBox)loader.getNamespace().get("chatMemoryOnlyCheck")).isSelected(),"Chat Dolphin defaults to memory-only");
                check(((TextArea)loader.getNamespace().get("inputArea")).isDisabled(),"saved Chat input disabled");
            });
            Map<String,String> beforeChats=new HashMap<>();try(var files=Files.walk(chats)){for(Path p:files.filter(Files::isRegularFile).toList())beforeChats.put(p.toString(),Files.readString(p));}
            fx(()-> {
                ((Button)chatLoader.get().getNamespace().get("sendButton")).fire();
                Stage privateStage=(Stage)Window.getWindows().stream().filter(w->w instanceof Stage s && s.getTitle().equals("Dolphin – nur anzeigen")).findFirst().orElseThrow();
                check(((TextArea)privateStage.getScene().lookup("#ephemeralInput")).getText().isEmpty(),"Chat dialog does not copy composer/history");
                privateStage.close();
                ((CheckBox)chatLoader.get().getNamespace().get("chatMemoryOnlyCheck")).setSelected(false);
                check(((Label)chatLoader.get().getNamespace().get("chatStorageModeLabel")).getText().startsWith("Mit Speicherung"),"Chat saved mode explicit");
            });
            Map<String,String> afterChats=new HashMap<>();try(var files=Files.walk(chats)){for(Path p:files.filter(Files::isRegularFile).toList())afterChats.put(p.toString(),Files.readString(p));}
            check(savedCalls.get()==0 && beforeChats.equals(afterChats),"Chat private branch never invokes saved route or alters history files");
            try(var paths=Files.walk(fixture)) {
                for(Path path:paths.filter(Files::isRegularFile).toList()) {
                    String content=Files.readString(path);
                    check(!content.contains("MEMORY-ONLY-PROMPT") && !content.contains("MEMORY-ONLY-ANSWER") && !content.contains("SAVED-FIELD-MUST-NOT-COPY") && !content.contains("CHAT-FIELD-MUST-NOT-COPY"),"content absent from fixture files");
                }
            }
            System.out.println("PASS: empty isolated UI, answer display, clear on close, Story/Chat private defaults, visible saved options, no content files or saved Chat calls");
            Platform.exit();
        } finally { server.stop(0); }
    }
}
