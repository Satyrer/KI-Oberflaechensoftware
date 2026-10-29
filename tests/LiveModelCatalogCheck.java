import javafx.application.Platform;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.scene.image.WritableImage;
import kioberflaeche.ai.ModelCatalogClient;
import kioberflaeche.ui.ModelPicker;
import kioberflaeche.story.StoryReviewStore;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/** Read-only live catalogue check. It never calls a generation, chat or transcription route. */
public class LiveModelCatalogCheck {
    static void check(boolean result,String text) { if(!result) throw new AssertionError(text); }
    static void fx(Runnable task) throws Exception { FutureTask<Void> call=new FutureTask<>(task,null); Platform.runLater(call); call.get(15,TimeUnit.SECONDS); }
    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((t,e)->{e.printStackTrace();System.exit(1);});
        Path audit=Path.of(args[1]); var catalog=ModelCatalogClient.workflow(args[0]); var options=catalog.load();
        boolean requireAllInstalled=args.length>2 && args[2].equals("--all-installed");
        check(options.stream().anyMatch(o->o.id().equals("mistral-rag") && o.available()),"Mistral RAG available");
        check(options.stream().anyMatch(o->o.id().equals("mistral-nemo") && o.available()),"NeMo available");
        check(options.stream().anyMatch(o->o.id().equals("deepseek-r1-8b")),"DeepSeek listed honestly");
        if(requireAllInstalled) check(options.stream().anyMatch(o->o.id().equals("deepseek-r1-8b") && o.available()),"DeepSeek installation complete and available");
        List<Object> proof=new ArrayList<>(); for(var option:options) proof.add(Map.of("id",option.id(),"available",option.available(),"tasks",option.tasks(),"label",option.label()));
        String proofPrefix=requireAllInstalled?"java-live-catalog-all-installed":"java-live-catalog";
        Files.writeString(audit.resolve(proofPrefix+".json"),StoryReviewStore.json(Map.of("endpoint",args[0]+"/webhook/schreib-ai/models","models",proof,"generationRequested",false,"requireAllInstalled",requireAllInstalled)));
        CountDownLatch ready=new CountDownLatch(1); Platform.startup(ready::countDown); ready.await();
        AtomicReference<ModelPicker> picker=new AtomicReference<>(); AtomicReference<ComboBox<ModelCatalogClient.Option>> box=new AtomicReference<>(); AtomicReference<Button> refresh=new AtomicReference<>(); AtomicReference<VBox> root=new AtomicReference<>();
        fx(()->{ var combo=new ComboBox<ModelCatalogClient.Option>(); combo.setMaxWidth(Double.MAX_VALUE); var hint=new Label(); hint.setWrapText(true); var button=new Button();
            var controls=new VBox(12,new Label("Live-Modellliste: Story-Kontext"),combo,button,hint); controls.getStyleClass().addAll("story-pane","model-controls");
            new Scene(controls,620,300).getStylesheets().add(LiveModelCatalogCheck.class.getResource("/css/chat.css").toExternalForm());
            picker.set(new ModelPicker(combo,hint,button,()->"story_context")); box.set(combo); refresh.set(button); root.set(controls); picker.get().configure(catalog);
        });
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30); boolean loaded=false;
        while(System.nanoTime()<end) { AtomicBoolean done=new AtomicBoolean(); fx(()->done.set(!refresh.get().isDisabled() && box.get().getItems().size()>1)); if(done.get()){loaded=true;break;} Thread.sleep(100); }
        check(loaded,"live UI catalogue loaded");
        fx(()->{ try {
            var deep=box.get().getItems().stream().filter(o->o.id().equals("deepseek-r1-8b")).findFirst().orElseThrow(); box.get().setValue(deep);
            if(!deep.available()) { boolean rejected=false; try {picker.get().selection();} catch(java.io.IOException expected){rejected=true;} check(rejected,"unfinished DeepSeek install cannot be started"); }
            box.get().setValue(box.get().getItems().stream().filter(o->o.id().equals("mistral-nemo")).findFirst().orElseThrow());
            check(picker.get().selection().equals("mistral-nemo"),"installed NeMo selectable");
            if(requireAllInstalled) {
                for(String id:List.of("mistral-rag","mistral-nemo","deepseek-r1-8b")) {
                    var choice=box.get().getItems().stream().filter(o->o.id().equals(id)).findFirst().orElseThrow();
                    check(choice.available(),"live catalogue confirms availability: "+id);
                    box.get().setValue(choice); check(picker.get().selection().equals(id),"actual JavaFX manual selection: "+id);
                }
            }
            root.get().resize(620,300); root.get().applyCss(); root.get().layout();
            WritableImage screenshot=root.get().snapshot(null,new WritableImage(620,300)); BufferedImage png=new BufferedImage(620,300,BufferedImage.TYPE_INT_ARGB);
            for(int y=0;y<300;y++)for(int x=0;x<620;x++)png.setRGB(x,y,screenshot.getPixelReader().getArgb(x,y));
            ImageIO.write(png,"png",audit.resolve(requireAllInstalled?"models-live-catalog-all-installed.png":"models-live-catalog.png").toFile());
        } catch(Exception e){throw new RuntimeException(e);} });
        System.out.println(requireAllInstalled
                ? "PASS: real VM catalogue and packaged JavaFX: Mistral-RAG, Mistral NeMo and DeepSeek all installed and manually selectable; no generation requested."
                : "PASS: real VM catalogue read, installed Mistral/NeMo, honest DeepSeek availability, actual JavaFX selection/rejection; no generation requested.");
        Platform.exit();
    }
}
