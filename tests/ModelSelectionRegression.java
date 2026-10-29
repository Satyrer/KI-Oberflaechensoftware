import com.sun.net.httpserver.HttpServer;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.*;
import kioberflaeche.ai.*;
import kioberflaeche.admin.N8nWebChatClient;
import kioberflaeche.config.AppConfig;
import kioberflaeche.controller.*;
import kioberflaeche.model.ChatMessage;
import kioberflaeche.media.TranscriptionService;
import kioberflaeche.storage.ChatStore;
import kioberflaeche.story.*;
import kioberflaeche.ui.ModelPicker;
import kioberflaeche.util.SimpleJson;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

public class ModelSelectionRegression {
    static int checks;
    static void check(boolean ok,String text) { if(!ok) throw new AssertionError(text); checks++; }
    interface Throwing { void run() throws Exception; }
    static void rejects(Throwing action,String name) throws Exception {
        try { action.run(); throw new AssertionError("Expected rejection: "+name); }
        catch(IOException|ExecutionException|CompletionException expected) { checks++; }
    }
    static void fx(Throwing action) throws Exception {
        FutureTask<Void> call=new FutureTask<>(()->{action.run();return null;}); Platform.runLater(call); call.get(20,TimeUnit.SECONDS);
    }
    static Map<String,Object> parse(String raw) { return SimpleJson.asObject(SimpleJson.parse(raw)); }
    static String json(Object value) { return StoryReviewStore.json(value); }
    static Map<String,Object> route(String id,String task) { return Map.of("requested",id,"selected",id,"model",id+":runtime","task",task,"mode","manual","reason","Gezielt ausgewaehlt."); }
    static String catalog(boolean available) { return json(Map.of("ok",true,"models",List.of(
            Map.of("id","deepseek-r1-8b","label","DeepSeek R1 8B","available",available,"tasks",List.of("chat","coding","story_context","story_write","transcription_structure"),"reason",available?"CPU, Genauigkeit zuerst.":"Noch nicht installiert."),
            Map.of("id","mistral-nemo","label","Mistral NeMo 12B","available",true,"tasks",List.of("chat","guard_test","story_context","story_write","transcription_structure"))
    ))); }
    static void image(Parent root,int width,int height,Path path) throws Exception {
        root.resize(width,height); root.applyCss(); root.layout();
        WritableImage screenshot=root.snapshot(null,new WritableImage(width,height));
        BufferedImage output=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<height;y++) for(int x=0;x<width;x++) output.setRGB(x,y,screenshot.getPixelReader().getArgb(x,y));
        ImageIO.write(output,"png",path.toFile());
    }
    static void waitFor(java.util.function.BooleanSupplier done) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<end) { AtomicBoolean answer=new AtomicBoolean(); fx(()->answer.set(done.getAsBoolean())); if(answer.get()) return; Thread.sleep(25); }
        throw new AssertionError("Timed out waiting for UI");
    }
    static Path workspace;
    public static class IsolatedStoryController extends StoryPaneController {
        @Override public void configure(SchreibAiWorkflowClient client) {
            try { var field=StoryPaneController.class.getDeclaredField("workspaceField"); field.setAccessible(true); ((TextField)field.get(this)).setText(workspace.toString()); }
            catch(Exception e) { throw new RuntimeException(e); }
            super.configure(client);
        }
    }
    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((thread,error)->{error.printStackTrace();System.exit(1);});
        Path audit=Path.of(args[0]), run=Files.createDirectory(audit.resolve("java-test-"+UUID.randomUUID()));
        System.setProperty("ki.modelPreferences",run.resolve("model-preferences.properties").toString());
        workspace=Files.createDirectories(run.resolve("stories")); StoryWorkspace.createProject(workspace,"Teststory","Neutrales Testbriefing.");
        StoryWorkspace.createProject(workspace,"ZweitesTestprojekt","Getrenntes Testbriefing.");
        AtomicReference<String> request=new AtomicReference<>(), response=new AtomicReference<>(), catalogResponse=new AtomicReference<>(catalog(true));
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            byte[] body=exchange.getRequestBody().readAllBytes(); request.set(new String(body,StandardCharsets.UTF_8));
            String data=exchange.getRequestURI().getPath().endsWith("/models")?catalogResponse.get():response.get();
            if(data==null) data="{}"; byte[] bytes=data.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8"); exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start(); String base="http://127.0.0.1:"+server.getAddress().getPort();
        var catalogue=ModelCatalogClient.workflow(base); var options=catalogue.load();
        check(options.size()==3 && options.get(0).id().equals("auto"),"catalog includes auto and real server IDs");
        check(!options.get(1).supports("guard_test") && options.get(2).supports("guard_test"),"task capability filtering");
        rejects(()->ModelCatalogClient.parse("{\"ok\":true}"),"catalog missing models");
        rejects(()->ModelCatalogClient.parse("{\"models\":[{\"id\":\"auto\"}]}"),"catalog duplicate reserved id");
        check(!ModelCatalogClient.parse(catalog(false)).get(1).available(),"uninstalled model remains visibly unavailable");
        var route=route("deepseek-r1-8b","coding");
        ModelRouting.verify(json(Map.of("modelRouting",route)),"deepseek-r1-8b"); checks++;
        rejects(()->ModelRouting.verify("{}","deepseek-r1-8b"),"missing actual selection confirmation");
        rejects(()->ModelRouting.verify(json(Map.of("modelRouting",route("mistral-nemo","coding"))),"deepseek-r1-8b"),"silent model substitution");
        rejects(()->ModelRouting.verify(json(Map.of("modelRouting",route,"analysis",Map.of("calls",List.of(Map.of("modelRouting",route("mistral-nemo","story_review")))))),"deepseek-r1-8b"),"manual story phase substitution");
        check(ModelRouting.summary(json(Map.of("analysis",Map.of("calls",List.of(Map.of("modelRouting",route),Map.of("modelRouting",route("mistral-nemo","story_write"))))))).contains("Szenentext"),"automatic phase decisions visible");
        List<ChatMessage> history=new ArrayList<>();
        for(int i=0;i<5;i++) { history.add(new ChatMessage(ChatMessage.Sender.USER,"user"+i,LocalDateTime.now())); history.add(new ChatMessage(ChatMessage.Sender.ASSISTANT,"answer"+i,LocalDateTime.now())); history.add(new ChatMessage(ChatMessage.Sender.SYSTEM,"model metadata",LocalDateTime.now())); }
        history.add(new ChatMessage(ChatMessage.Sender.USER,"unfinished",LocalDateTime.now()));
        check(AiClient.recentHistory(history).size()==6 && AiClient.recentHistory(history).getFirst().text().equals("user2"),"three complete pairs without metadata or unfinished prompt");
        String input="aktueller Auftrag \u0001 mit Umlaut ä";
        response.set(json(Map.of("message",Map.of("role","assistant","content","Echte Antwort"),"modelRouting",route)));
        var direct=new HttpAiClient(base+"/api/chat","","legacy-tag",20);
        var answer=direct.askWithModel("session",history,input,"deepseek-r1-8b","coding").get(10,TimeUnit.SECONDS);
        var sent=parse(request.get());
        check(answer.text().equals("Echte Antwort"),"Ollama nested message content rather than role");
        check(sent.get("modelSelection").equals("deepseek-r1-8b") && sent.get("task").equals("coding"),"direct request routes explicit model/task");
        var messages=SimpleJson.asArray(sent.get("messages"));
        check(messages.size()==7 && SimpleJson.asObject(messages.getLast()).get("content").equals(input),"current prompt appears once after three pairs with controls preserved");
        response.set(json(Map.of("output","n8n Antwort","modelRouting",route)));
        var n8n=new N8nChatWebhookClient(base+"/webhook/ki/chat","",20);
        check(n8n.askWithModel("session",history,input,"deepseek-r1-8b","coding").get().text().equals("n8n Antwort"),"n8n answer");
        sent=parse(request.get());
        check(SimpleJson.asArray(sent.get("history")).size()==6 && SimpleJson.asArray(sent.get("messages")).size()==7,"n8n coherent history/messages");
        check(sent.get("chatInput").equals(input) && sent.get("modelSelection").equals("deepseek-r1-8b"),"n8n exact model and prompt");
        response.set("{\"output\":\"unconfirmed\"}");
        rejects(()->n8n.askWithModel("session",List.of(),"x","deepseek-r1-8b","chat").get(),"unconfirmed n8n explicit model");
        rejects(()->direct.askWithModel("session",List.of(),"x","deepseek-r1-8b","chat").get(),"unconfirmed direct explicit model");
        response.set(json(Map.of("ok",false,"message","Testfehler","modelRouting",route)));
        rejects(()->n8n.askWithModel("session",List.of(),"x","auto","chat").get(),"HTTP200 typed n8n failure is not an answer");
        rejects(()->direct.askWithModel("session",List.of(),"x","deepseek-r1-8b","chat").get(),"HTTP200 typed direct failure is not an answer");
        var story=new SchreibAiWorkflowClient(base,30);
        response.set(json(Map.of("ok",true,"projectId","Teststory","file_changes",List.of(Map.of("path","overview/test.md","content","Kontext")),"modelRouting",route)));
        var storyResult=story.buildContext("Teststory","Deutsch","Auswerten",List.of(new SchreibAiWorkflowClient.StoryDocument("sources/test.md","Original")),List.of(),"deepseek-r1-8b");
        check(parse(request.get()).get("modelSelection").equals("deepseek-r1-8b") && storyResult.changes().size()==1,"story request selection retained with existing review contract");
        story.writeScene("Teststory","Deutsch","Schreiben","manuscript/test.md",List.of(new SchreibAiWorkflowClient.StoryDocument("sources/test.md","Original")),List.of(),"deepseek-r1-8b");
        check(parse(request.get()).get("target").equals("manuscript/test.md"),"scene request explicit model and target");
        response.set(json(Map.of("ok",true,"file_changes",List.of(),"modelRouting",route("mistral-nemo","story_context"))));
        rejects(()->story.buildContext("Teststory","Deutsch","Auswerten",List.of(),List.of(),"deepseek-r1-8b"),"story substitution rejects before applying");
        Path testConfig=run.resolve("test.properties"); Files.writeString(testConfig,"n8n.schreibAi.baseUrl="+base+"\ntranscription.n8n.webhookUrl="+base+"/transcribe\n",StandardCharsets.UTF_8);
        System.setProperty("ki.config",testConfig.toString()); System.setProperty("n8n.schreibAi.baseUrl",base); System.setProperty("transcription.n8n.webhookUrl",base+"/transcribe");
        Path audio=run.resolve("fixture.wav"), out=run.resolve("transcript.json"); Files.write(audio,new byte[]{0,1,2,3});
        response.set(json(Map.of("ok",true,"text","Unveraendertes Transkript.","asr",Map.of("model","large-v3"),"ai",Map.of("ok",true,"modelRouting",route("deepseek-r1-8b","transcription_structure")))));
        var transcription=new TranscriptionService(AppConfig.load());
        var trans=transcription.transcribe(audio,out,"de","large-v3","structured","","","","deepseek-r1-8b");
        check(request.get().contains("name=\"modelSelection\"\r\n\r\ndeepseek-r1-8b") && request.get().contains("large-v3"),"separate ASR and text-model multipart fields");
        check(trans.summary().contains("deepseek-r1-8b") && Files.readString(run.resolve("transcript.txt")).equals("Unveraendertes Transkript."),"actual structure model reported without changing original");
        response.set(json(Map.of("ok",true,"text","Changed","asr",Map.of("model","large-v3"),"ai",Map.of("ok",true,"modelRouting",route("mistral-nemo","transcription_structure")))));
        rejects(()->transcription.transcribe(audio,out,"de","large-v3","structured","","","","deepseek-r1-8b"),"structure model mismatch rejects");
        check(Files.readString(run.resolve("transcript.txt")).equals("Unveraendertes Transkript."),"model mismatch preserves output");
        System.out.println("PASS: catalog, strict explicit routing, history, control escaping, direct/n8n/Story/ASR HTTP contracts ("+checks+" checks)");

        CountDownLatch started=new CountDownLatch(1); Platform.startup(started::countDown); started.await();
        AtomicReference<ModelPicker> picker=new AtomicReference<>(); AtomicReference<ComboBox<ModelCatalogClient.Option>> combo=new AtomicReference<>(); AtomicReference<Button> refresh=new AtomicReference<>(); AtomicReference<String> task=new AtomicReference<>("chat");
        AtomicReference<Label> pickerHint=new AtomicReference<>();
        fx(()->{ var box=new ComboBox<ModelCatalogClient.Option>(); var button=new Button(); var label=new Label(); pickerHint.set(label); picker.set(new ModelPicker(box,label,button,task::get)); combo.set(box); refresh.set(button); picker.get().usePreference("test.model"); picker.get().configure(catalogue); });
        waitFor(()->combo.get().getItems().size()==3);
        fx(()->{ combo.get().setValue(combo.get().getItems().get(1)); check(picker.get().selection().equals("deepseek-r1-8b"),"UI manual choice enabled after catalogue"); picker.get().setLocked(true); check(combo.get().isDisabled() && refresh.get().isDisabled(),"UI locks selection while job runs"); picker.get().setLocked(false); task.set("guard_test"); picker.get().refilter(); check(combo.get().getValue().id().equals("deepseek-r1-8b"),"incompatible task does not silently switch explicit model"); rejects(()->picker.get().selection(),"unsupported manual task"); task.set("chat"); picker.get().refilter(); });
        catalogResponse.set(catalog(false)); fx(()->refresh.get().fire()); waitFor(()->!refresh.get().isDisabled());
        fx(()->{ check(combo.get().getValue().id().equals("deepseek-r1-8b") && !combo.get().getValue().available(),"refresh preserves now unavailable explicit model"); rejects(()->picker.get().selection(),"unavailable model cannot start"); });
        fx(()->{
            var restored=new ComboBox<ModelCatalogClient.Option>(); var second=new ModelPicker(restored,new Label(),new Button(),()->"chat"); second.usePreference("test.model");
            check(restored.getValue().id().equals("deepseek-r1-8b"),"new picker instance restores explicit persisted selection before loading catalogue");
            rejects(()->second.selection(),"restored selection is unconfirmed until catalogue loads");
        });
        catalogResponse.set("{}"); fx(()->refresh.get().fire()); waitFor(()->!refresh.get().isDisabled());
        fx(()->{ task.set("coding"); picker.get().refilter(); check(pickerHint.get().getText().contains("nicht erreichbar"),"catalogue error survives task switch and refilter"); rejects(()->picker.get().selection(),"stale manual catalogue remains blocked"); });
        catalogResponse.set(catalog(true));
        AtomicReference<FXMLLoader> full=new AtomicReference<>(); AtomicReference<Parent> root=new AtomicReference<>();
        fx(()->{
            FXMLLoader loader=new FXMLLoader(ModelSelectionRegression.class.getResource("/fxml/chat_window.fxml"));
            loader.setControllerFactory(type->{try {
                if(type==ChatController.class) return new ChatController(n8n,new ChatStore(run.resolve("chats")),new N8nWebChatClient("","","",30),story);
                if(type==StoryPaneController.class) return new IsolatedStoryController();
                return type.getDeclaredConstructor().newInstance();
            } catch(Exception e) {throw new RuntimeException(e);}});
            Parent view=loader.load(); new Scene(view,1280,900).getStylesheets().add(ModelSelectionRegression.class.getResource("/css/chat.css").toExternalForm()); root.set(view); full.set(loader);
            check(loader.getNamespace().get("chatModelCombo") instanceof ComboBox,"chat FXML wires model selector");
            check(loader.getNamespace().get("storyTabController") instanceof StoryPaneController,"story FXML still loads through includes");
            check(loader.getNamespace().get("agentTabController") instanceof AgentPaneController,"agent FXML loads through includes");
        });
        waitFor(()->((ComboBox<?>)full.get().getNamespace().get("chatModelCombo")).getItems().size()==3);
        response.set(json(Map.of("output","Antwort aus dem isolierten Test.","modelRouting",route)));
        fx(()->{
            var models=(ComboBox<ModelCatalogClient.Option>)full.get().getNamespace().get("chatModelCombo"); models.setValue(models.getItems().get(1));
            ((ComboBox<ChatController.ChatTask>)full.get().getNamespace().get("chatTaskCombo")).setValue(ChatController.ChatTask.CODING);
            ((TextArea)full.get().getNamespace().get("inputArea")).setText("Bitte pruefe diese neutrale Testfunktion.");
            ((Button)full.get().getNamespace().get("sendButton")).fire();
            check(models.isDisabled(),"actual chat submit locks model control");
        });
        waitFor(()->!((Button)full.get().getNamespace().get("sendButton")).isDisabled());
        check(parse(request.get()).get("modelSelection").equals("deepseek-r1-8b") && parse(request.get()).get("task").equals("coding"),"actual chat button sends chosen model/task");
        var savedChat=new ChatStore(run.resolve("chats")).loadAll().getFirst();
        check(savedChat.messages().stream().anyMatch(m->m.sender()==ChatMessage.Sender.SYSTEM && m.text().contains("deepseek-r1-8b")),"actual model decision persists in isolated chat");
        fx(()->{
            TabPane tabs=(TabPane)root.get().lookup(".main-tabs"); tabs.getSelectionModel().select(1); root.get().applyCss(); root.get().layout();
            var models=(ComboBox<ModelCatalogClient.Option>)root.get().lookup("#storyModelCombo");
            var projects=(ComboBox<String>)root.get().lookup("#projectCombo");
            models.setValue(models.getItems().stream().filter(o->o.id().equals("mistral-nemo")).findFirst().orElseThrow());
            projects.setValue("ZweitesTestprojekt"); check(models.getValue().id().equals("auto"),"different story project starts with its own selection");
            projects.setValue("Teststory"); check(models.getValue().id().equals("mistral-nemo"),"story model survives project switches");
            check(ModelPreferences.get("story."+StoryWorkspace.hash(workspace.resolve("Teststory").toAbsolutePath().normalize().toString()),"auto").equals("mistral-nemo"),"story selection persisted separately by project");
            tabs.getSelectionModel().select(0);
        });
        fx(()->{
            image(root.get(),1280,900,audit.resolve("models-chat-1280.png"));
            TabPane tabs=(TabPane)root.get().lookup(".main-tabs"); tabs.getSelectionModel().select(1); image(root.get(),1280,900,audit.resolve("models-story-1280.png")); image(root.get(),920,700,audit.resolve("models-story-920.png"));
            tabs.getSelectionModel().select(3);
            ((TextField)root.get().lookup("#audioSourceField")).setText(audio.toString());
            ((TextField)root.get().lookup("#transcriptTargetDirectoryField")).setText(run.toString());
            ((TextField)root.get().lookup("#transcriptTargetNameField")).setText("test-transkript.json");
            ((ComboBox<String>)root.get().lookup("#transcriptOutputModeComboBox")).setValue("structured");
            image(root.get(),1280,900,audit.resolve("models-transcription-1280.png"));
        });
        System.out.println("PASS: JavaFX model refresh/availability/locks, complete 5-tab FXML load, isolated screenshots ("+checks+" total checks)");
        Platform.exit(); server.stop(0);
    }
}
