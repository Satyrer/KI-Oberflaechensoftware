import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import kioberflaeche.controller.AgentPaneController;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

public class AgentUiRegression {
    static int checks;
    static void check(boolean value,String text) { if(!value) throw new AssertionError(text);checks++; }
    interface Throwing { void run() throws Exception; }
    static void fx(Throwing action) throws Exception {
        FutureTask<Void> task=new FutureTask<>(()->{action.run();return null;});
        Platform.runLater(task);task.get(10,TimeUnit.SECONDS);
    }
    static void waitFor(BooleanSupplier condition) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
        while(System.nanoTime()<end) {
            AtomicBoolean done=new AtomicBoolean();fx(()->done.set(condition.getAsBoolean()));
            if(done.get()) return;Thread.sleep(30);
        }
        throw new AssertionError("UI wait timed out");
    }
    @SuppressWarnings("unchecked") static <T> T field(FXMLLoader loader,String id) { return (T)loader.getNamespace().get(id); }
    static void click(FXMLLoader loader,String id) { ((Button)field(loader,id)).fire(); }
    static void shot(Parent root,int width,int height,Path path) throws Exception {
        root.resize(width,height);root.applyCss();root.layout();
        WritableImage pixels=root.snapshot(null,new WritableImage(width,height));
        BufferedImage output=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<height;y++) for(int x=0;x<width;x++) output.setRGB(x,y,pixels.getPixelReader().getArgb(x,y));
        ImageIO.write(output,"png",path.toFile());
    }
    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((thread,error)->{error.printStackTrace();System.exit(1);});
        Path audit=Path.of(args[0]);
        CountDownLatch startup=new CountDownLatch(1);Platform.startup(startup::countDown);startup.await();
        try(AgentFixture fixture=new AgentFixture()) {
            fixture.modelSeconds=3600;fixture.remainingMilliseconds=3475000;
            FXMLLoader loader=new FXMLLoader(AgentUiRegression.class.getResource("/fxml/agent_tab.fxml"));
            AtomicReference<Parent> root=new AtomicReference<>();
            fx(()->{
                root.set(loader.load());
                Scene scene=new Scene(root.get(),820,760);
                scene.getStylesheets().add(AgentUiRegression.class.getResource("/css/chat.css").toExternalForm());
                ((AgentPaneController)loader.getController()).configure(fixture.base());
                check(((Spinner<?>)field(loader,"modelSeconds")).getValue().equals(1800),"1800 second default");
                check(((Spinner<?>)field(loader,"actionBudget")).getValue().equals(15),"fifteen action default");
                check(((Spinner<?>)field(loader,"roundBudget")).getValue().equals(2),"two repair round default");
                check(((Button)field(loader,"startButton")).isDisabled(),"start disabled without authentication");
                check(((Button)field(loader,"applyButton")).isDisabled(),"apply disabled without review");
                check(((Label)field(loader,"qualityStatus")).getText().contains("noch keine Prüfung"),"initial quality not claimed");
                shot(root.get(),820,760,audit.resolve("agent-connection.png"));
            });
            Thread.sleep(200);
            check(fixture.calls.get()==0,"no background requests before explicit connect");
            fx(()->{ ((PasswordField)field(loader,"projectToken")).setText(AgentFixture.TOKEN);click(loader,"connectButton"); });
            waitFor(()->!((ComboBox<?>)field(loader,"agentProjectCombo")).getItems().isEmpty());
            fx(()->{
                check(((PasswordField)field(loader,"projectToken")).getText().isEmpty(),"token field cleared after connection");
                check(((Label)field(loader,"qualityProfileHint")).getText().contains("omp01-v1"),"quality profile visible before submission");
                check(((Button)field(loader,"startButton")).isDisabled()==false,"authenticated start available");
                ((TextArea)field(loader,"taskText")).setText("Repariere die Addition und prüfe das Objektmodell.");
                ((Spinner<Integer>)field(loader,"modelSeconds")).getValueFactory().setValue(3600);
                ((CheckBox)field(loader,"umlEnabled")).setSelected(true);
                ((TextArea)field(loader,"requiredDiagrams")).setText("objektmodell object");
                shot(root.get(),820,760,audit.resolve("agent-request.png"));
                fixture.firstStartFails=true;click(loader,"startButton");
            });
            waitFor(()->((Button)field(loader,"startButton")).getText().equals("Start erneut anfragen") && !((Button)field(loader,"startButton")).isDisabled());
            fx(()->{
                check(((TextArea)field(loader,"taskText")).isDisabled(),"unknown submit locks immutable draft");
                click(loader,"startButton");
            });
            waitFor(()->((TextField)field(loader,"displayedJobId")).getText().equals(AgentFixture.ID));
            check(fixture.submitted.size()==2 && fixture.submitted.get(0).equals(fixture.submitted.get(1)),"idempotent retry sends identical request and key");
            var submitted=(java.util.Map<?,?>)kioberflaeche.agent.AgentJson.parse(fixture.submitted.getFirst());
            check(new java.math.BigDecimal(15).equals(submitted.get("actions"))
                    && new java.math.BigDecimal(2).equals(submitted.get("rounds"))
                    && new java.math.BigDecimal(3600).equals(submitted.get("modelSeconds")),"UI submits deliberately extended shared time budget with established action/repair defaults");
            fx(()->{
                check(((Label)field(loader,"budgetLabel")).getText().contains("125,0"),"running result beyond 90 seconds accepted");
                check(((TextArea)field(loader,"stageOverview")).getText().contains("Ältere Aufträge"),"legacy job diagnostics visibly unavailable");
                check(!((Button)field(loader,"cancelButton")).isDisabled(),"long running job can be cancelled");
                check(((Button)field(loader,"newButton")).isDisabled(),"no replacement during active job");
                ((TabPane)field(loader,"agentSections")).getSelectionModel().select(1);
                shot(root.get(),820,760,audit.resolve("agent-running.png"));
            });
            fixture.usedMilliseconds=3599600;fixture.remainingMilliseconds=400;fixture.reservedMilliseconds=3283000;
            waitFor(()->!((Button)field(loader,"refreshStatusButton")).isDisabled());
            fx(()->click(loader,"refreshStatusButton"));
            waitFor(()->((Label)field(loader,"budgetLabel")).getText().contains("316,6"));
            fx(()->{
                String budget=((Label)field(loader,"budgetLabel")).getText();
                check(budget.contains("Abgerechnete Agentenzeit: 316,6 s") && !budget.contains("3599,6"),"reservation not displayed as consumed time");
                check(budget.contains("Restbudget nach dieser Abrechnung: 3283,4 s"),"unsettled reservation remains in budget before final accounting");
                check(budget.contains("reserviert: 3283,0 s") && budget.contains("erst nach Abschluss abgerechnet"),"running reservation and pending real consumption explicit");
                check(Math.abs(((ProgressBar)field(loader,"budgetProgress")).getProgress()-316600.0/3600000)<0.000001,"progress bar uses settled consumption only");
                shot(root.get(),820,760,audit.resolve("agent-running-reservation.png"));
            });
            fixture.qualitySummary=AgentFixture.quality("failed");
            fixture.diagnostics=AgentFixture.diagnostics();
            waitFor(()->!((Button)field(loader,"refreshStatusButton")).isDisabled());
            fx(()->click(loader,"refreshStatusButton"));
            waitFor(()->((Label)field(loader,"qualityStatus")).getText().contains("nicht bestanden"));
            fx(()->{
                check(((Label)field(loader,"qualityStatus")).getText().contains("java.required-methods"),"quality failure visible without review");
                String overview=((TextArea)field(loader,"stageOverview")).getText();
                check(overview.contains("Diagrammerzeugung: fehlgeschlagen"),"actual rendering failure visible");
                check(overview.contains("Kompilierung: nicht ausgeführt") && overview.contains("Verhaltenstests: nicht ausgeführt"),"unstarted stages not falsely failed");
                check(overview.contains("gelesen: 2") && overview.contains("benötigt: 4"),"source coverage counts visible");
                check(((TextArea)field(loader,"stageEvidence")).getText().contains("a".repeat(64)),"full evidence hash available");
                check(!overview.contains("PRIVATE-DRAFT") && !((TextArea)field(loader,"stageEvidence")).getText().contains("PRIVATE-DRAFT"),"rejected model text never displayed");
                check(((Button)field(loader,"applyButton")).isDisabled(),"diagnostics cannot unlock adoption");
                shot(root.get(),820,760,audit.resolve("agent-stage-diagnostics.png"));
            });
            fixture.qualitySummary=AgentFixture.quality("passed");fixture.status="awaiting_review";
            waitFor(()->!((Button)field(loader,"refreshStatusButton")).isDisabled());
            fx(()->click(loader,"refreshStatusButton"));
            waitFor(()->!((Button)field(loader,"reviewButton")).isDisabled());
            fx(()->click(loader,"reviewButton"));
            waitFor(()->!((TextArea)field(loader,"diffText")).getText().isEmpty());
            fx(()->{
                check(((TextArea)field(loader,"diffText")).getText().equals(AgentFixture.DIFF),"full plain-text diff");
                check(((TextArea)field(loader,"checkText")).getText().contains("passed"),"test evidence shown");
                check(((TextArea)field(loader,"checkText")).getText().contains("Verhaltenstests ausgeführt: ja"),"executed behavior evidence in review");
                check(((Label)field(loader,"qualityStatus")).getText().contains("Fachliche Gesamtprüfung bleibt erforderlich"),"bounded profile is not general task acceptance");
                check(((Button)field(loader,"applyButton")).isDisabled(),"review alone never approves");
                ((TabPane)field(loader,"agentSections")).getSelectionModel().select(2);
                shot(root.get(),820,760,audit.resolve("agent-review.png"));
                click(loader,"diagramButton");
            });
            waitFor(()->((ImageView)field(loader,"diagramImage")).getImage()!=null);
            fx(()->{
                ((TabPane)field(loader,"reviewSections")).getSelectionModel().select(3);
                shot(root.get(),820,760,audit.resolve("agent-diagram.png"));
                ((CheckBox)field(loader,"reviewedCheck")).setSelected(true);
                ((PasswordField)field(loader,"operatorToken")).setText(AgentFixture.OPERATOR);
                ((TextField)field(loader,"operatorName")).setText("Testperson");
                ((TextField)field(loader,"confirmationInput")).setText("APPROVE wrong");
                check(((Button)field(loader,"applyButton")).isDisabled(),"wrong review confirmation blocked");
                ((TextField)field(loader,"confirmationInput")).setText("APPROVE "+AgentFixture.REVIEW);
                check(!((Button)field(loader,"applyButton")).isDisabled(),"exact bound confirmation enables explicit action");
                ((TabPane)field(loader,"agentSections")).getSelectionModel().select(3);
                shot(root.get(),820,760,audit.resolve("agent-adoption.png"));
            });
            check(fixture.applies.get()==0,"no adoption without explicit click and confirmation");
            fixture.usedMilliseconds=640000;fixture.remainingMilliseconds=2960000;fixture.reservedMilliseconds=0;
            fixture.status="completed";
            fx(()->click(loader,"refreshStatusButton"));
            waitFor(()->((Label)field(loader,"agentStatus")).getText().contains("abgeschlossen"));
            fx(()->{
                check(((PasswordField)field(loader,"operatorToken")).getText().isEmpty(),"changing state clears operator credential");
                String budget=((Label)field(loader,"budgetLabel")).getText();
                check(budget.contains("Abgerechnete Agentenzeit: 640,0 s") && budget.contains("Restbudget nach dieser Abrechnung: 2960,0 s"),"completed call displays final accounting");
                check(!budget.contains("reserviert") && !budget.contains("erst nach Abschluss"),"completed state clears pending reservation language");
                check(Math.abs(((ProgressBar)field(loader,"budgetProgress")).getProgress()-640000.0/3600000)<0.000001,"completed bar reflects final consumed budget");
                ((TabPane)field(loader,"agentSections")).getSelectionModel().select(1);
                shot(root.get(),820,760,audit.resolve("agent-completed-budget.png"));
                click(loader,"reviewButton");
            });
            waitFor(()->!((TextArea)field(loader,"diffText")).getText().isEmpty());
            fx(()->check(((Button)field(loader,"applyButton")).isDisabled(),"completed review cannot be adopted again"));
            fixture.phaseOverride="adoption_recovery_required";
            fixture.status="recovery_required";
            fx(()->click(loader,"refreshStatusButton"));
            waitFor(()->((Label)field(loader,"agentStatus")).getText().contains("manuelle Übernahmeprüfung"));
            fx(()->{
                check(((Button)field(loader,"resumeButton")).isDisabled(),"interrupted adoption cannot be replayed");
                check(((Button)field(loader,"cancelButton")).isDisabled(),"interrupted adoption cannot be cancelled");
            });
            fixture.phaseOverride="adoption_started";
            fixture.status="running";
            fx(()->click(loader,"refreshStatusButton"));
            waitFor(()->!((Button)field(loader,"refreshStatusButton")).isDisabled());
            fx(()->check(((Button)field(loader,"cancelButton")).isDisabled(),"running adoption cannot be cancelled"));
            fixture.phaseOverride="";
            fixture.status="recovery_required";
            fx(()->click(loader,"refreshStatusButton"));
            waitFor(()->!((Button)field(loader,"resumeButton")).isDisabled());
            fx(()->click(loader,"resumeButton"));
            waitFor(()->!((Button)field(loader,"cancelButton")).isDisabled());
            fixture.fault="context";
            fx(()->click(loader,"refreshStatusButton"));
            waitFor(()->((Label)field(loader,"agentStatus")).getText().contains("Dokumentfreigabe"));
            fx(()->check(!((Button)field(loader,"cancelButton")).isDisabled(),"revoked context does not block cancellation"));
            fx(()->click(loader,"cancelButton"));
            waitFor(()->((Label)field(loader,"agentStatus")).getText().contains("abgebrochen"));
            fixture.fault="";
            fx(()->check(((Button)field(loader,"cancelButton")).isDisabled(),"cancel completion"));
            fixture.reached=new CountDownLatch(1);fixture.delayMillis=900;
            fx(()->click(loader,"refreshStatusButton"));
            check(fixture.reached.await(3,TimeUnit.SECONDS),"delayed status underway");
            long before=System.nanoTime();
            fx(()->click(loader,"disconnectButton"));
            check(System.nanoTime()-before<TimeUnit.MILLISECONDS.toNanos(500),"FX remains responsive during HTTP");
            Thread.sleep(1150);
            fx(()->{
                check(((TextField)field(loader,"displayedJobId")).getText().isEmpty(),"late result cannot restore disconnected job");
                check(((ComboBox<?>)field(loader,"agentProjectCombo")).getItems().isEmpty(),"late result cannot restore projects");
                check(((Button)field(loader,"startButton")).isDisabled(),"late result cannot enable actions");
                check(!((TextArea)field(loader,"stageEvidence")).getText().contains("a".repeat(64)),"disconnect clears stale diagnostics");
            });
            fixture.delayMillis=0;fixture.status="completed";
            fx(()->{((PasswordField)field(loader,"projectToken")).setText(AgentFixture.TOKEN);click(loader,"connectButton");});
            waitFor(()->!((ComboBox<?>)field(loader,"agentProjectCombo")).getItems().isEmpty());
            fx(()->{((TextField)field(loader,"reopenId")).setText(AgentFixture.ID);click(loader,"reopenButton");});
            waitFor(()->((TextField)field(loader,"displayedJobId")).getText().equals(AgentFixture.ID));
            fx(()->{
                check(((Label)field(loader,"agentStatus")).getText().contains("abgeschlossen"),"completed job reopened by ID");
                check(!((Button)field(loader,"reviewButton")).isDisabled(),"reopened result remains reviewable");
                click(loader,"disconnectButton");
            });
            System.out.println("PASS: "+checks+" UI checks; explicit connection/start, stable retry, long-call status, review, PNG, recovery, cancellation and stale-response isolation.");
        } finally { Platform.exit(); }
    }
}
