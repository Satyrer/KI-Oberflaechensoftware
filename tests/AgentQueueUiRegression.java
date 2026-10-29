import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.*;
import javafx.scene.control.*;
import kioberflaeche.controller.AgentPaneController;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class AgentQueueUiRegression extends AgentUiRegression {
    static Parent queueRoot;
    static Button button(String id) { return (Button)queueRoot.lookup("#"+id); }
    static Label label(String id) { return (Label)queueRoot.lookup("#"+id); }
    static ListView<?> list() { return (ListView<?>)queueRoot.lookup("#queueItems"); }
    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((thread,error)->{error.printStackTrace();System.exit(1);});
        CountDownLatch startup=new CountDownLatch(1);Platform.startup(startup::countDown);startup.await();
        try(AgentFixture fixture=new AgentFixture()) {
            FXMLLoader loader=new FXMLLoader(AgentUiRegression.class.getResource("/fxml/agent_tab.fxml"));
            AtomicReference<Parent> root=new AtomicReference<>();
            fx(()->{
                root.set(loader.load());new Scene(root.get(),820,760);
                root.get().getStylesheets().add(AgentUiRegression.class.getResource("/css/chat.css").toExternalForm());
                queueRoot=field(loader,"queuePane");
                ((TabPane)field(loader,"agentSections")).getSelectionModel().select(4);
                root.get().applyCss();root.get().layout();
                ((AgentPaneController)loader.getController()).configure(fixture.base());
                check(((Button)field(loader,"queueAddButton")).isDisabled(),"cannot enqueue before authentication");
                ((PasswordField)field(loader,"projectToken")).setText(AgentFixture.TOKEN);click(loader,"connectButton");
            });
            waitFor(()->label("queueSummary").getText().contains("0 wartend") && !button("queueRefreshButton").isDisabled());
            fx(()->{
                check(label("queueSummary").getText().contains("pausiert"),"paused state visible");
                ((TextArea)field(loader,"taskText")).setText("Erste synthetische Queue-Aufgabe");
                fixture.failQueueAfterCommit=true;click(loader,"queueAddButton");
            });
            waitFor(()->!button("queueRetryButton").isDisabled());
            fx(()->{
                check(((TextArea)field(loader,"taskText")).isDisabled(),"uncertain enqueue locks exact task package");
                check(button("queueClearButton").isDisabled(),"unresolved mutation blocks competing command");
                button("queueRetryButton").fire();
            });
            waitFor(()->list().getItems().size()==1 && !button("queueNextButton").isDisabled());
            check(fixture.queueAdds.get()==1 && fixture.queueBodies.get(0).equals(fixture.queueBodies.get(1)),"UI retry exact immutable request");
            fx(()->{
                list().getSelectionModel().selectFirst();
                check(!button("queueRemoveButton").isDisabled(),"waiting selected removable");
                check(button("queueOpenButton").isDisabled(),"held selection not confused with active job");
                fixture.queueConflict=true;
                button("queueNextButton").fire();
            });
            waitFor(()->label("queueError").getText().contains("project_source_changed") && !button("queueNextButton").isDisabled());
            fx(()->{
                check(label("queueError").getText().contains("00000000000000000000000000000001"),"409 admission reload identifies blocked entry");
                check(fixture.active.isEmpty() && fixture.waiting.size()==1,"admission rejection preserves waiting task");
                fixture.queueError="";fixture.blockedEntry="";button("queueNextButton").fire();
            });
            waitFor(()->label("queueSummary").getText().contains("1 freigegeben") && !button("queueRefreshButton").isDisabled());
            fx(()->{
                list().getSelectionModel().selectFirst();
                check(button("queueRemoveButton").isDisabled(),"released item cannot be removed as waiting");
                check(label("queueSummary").getText().contains("pausiert"),"one release preserves pause");
                button("queueOpenButton").fire();
            });
            waitFor(()->((TextField)field(loader,"displayedJobId")).getText().equals(AgentFixture.ID));
            fx(()->{
                check(!((TextArea)field(loader,"taskText")).isDisabled(),"new queued draft editable during active job");
                check(((Button)field(loader,"startButton")).isDisabled(),"active job not replaced by direct start");
                ((TextArea)field(loader,"taskText")).setText("Zweite synthetische Queue-Aufgabe");click(loader,"queueAddButton");
            });
            waitFor(()->label("queueSummary").getText().contains("1 wartend") && !button("queueClearButton").isDisabled());
            fx(()->{
                ((TabPane)field(loader,"agentSections")).getSelectionModel().select(4);
                list().getSelectionModel().select(1);
                shot(root.get(),820,760,Path.of(args[0]).resolve("agent-queue.png"));
                button("queueClearButton").fire();
            });
            waitFor(()->label("queueSummary").getText().contains("0 wartend") && !button("queueRefreshButton").isDisabled());
            check(fixture.active.size()==1 && fixture.waiting.isEmpty(),"clear preserves active server job");
            fx(()->{button("queueResumeButton").fire();});
            waitFor(()->label("queueSummary").getText().contains("automatisch fortlaufend") && !button("queuePauseButton").isDisabled());
            fx(()->button("queuePauseButton").fire());
            waitFor(()->label("queueSummary").getText().contains("pausiert") && !button("queueRefreshButton").isDisabled());
            fixture.actionUsage = Map.of("budgetPolicy","normal-plus-uml-v1","normalActionBudget",10,"normalActionsUsed",9,
                    "totalActionBudget",13,"totalActionsUsed",12,"actionsUsed",12,"umlAttemptBudget",3,
                    "umlAttempts",java.util.List.of(Map.of("diagramId","overview","diagramType","class","attemptsUsed",3,"attemptBudget",3)));
            fx(()->click(loader,"refreshStatusButton"));
            waitFor(()->((Label)field(loader,"budgetLabel")).getText().contains("Gesamt 12/13"));
            fx(()->check(((Label)field(loader,"budgetLabel")).getText().contains("9/10") && ((Label)field(loader,"budgetLabel")).getText().contains("3/3 Versuche"),"split action budgets shown"));
            fixture.delayMillis=350;
            fx(()->{button("queueRefreshButton").fire();click(loader,"disconnectButton");});
            Thread.sleep(600);
            fx(()->check(list().getItems().isEmpty() && label("queueSummary").getText().contains("nach dem Verbinden"),"late queue read cannot repopulate disconnected view"));
            check(fixture.applies.get()==0 && fixture.starts.get()==0,"queue flow never auto-adopts or direct-starts");
        } finally {Platform.exit();}
        System.out.println("PASS: "+checks+" queue UI checks; snapshots and synthetic HTTP only.");
    }
}
