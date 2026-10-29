package kioberflaeche.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import kioberflaeche.ai.EphemeralTextClient;
import java.nio.charset.StandardCharsets;

/** Isolated editor: never receives project fields, drafts, chat history or persistence stores. */
public final class EphemeralTextDialog {
    private EphemeralTextDialog() {}
    public static Stage open(Window owner,EphemeralTextClient client) {
        Stage stage=new Stage();
        if(owner!=null)stage.initOwner(owner);
        stage.setTitle("Dolphin – nur anzeigen");
        TextArea input=new TextArea(), output=new TextArea();
        input.setId("ephemeralInput");output.setId("ephemeralOutput");
        input.setWrapText(true);output.setWrapText(true);output.setEditable(false);
        input.setPromptText("Textauftrag und benoetigten Kontext hier eingeben. Projektinhalte werden nicht automatisch uebernommen.");
        input.setPrefRowCount(8);output.setPrefRowCount(12);
        ComboBox<String> language=new ComboBox<>();language.getItems().setAll("Deutsch","English");language.setValue("Deutsch");
        Label notice=new Label("Ein- und Ausgabe bleiben in dieser Ansicht. Keine Projektdatei, Zwischenstaende, Chat-Historie oder automatische Exporte. Beim Schliessen wird die Ansicht geleert.");notice.setWrapText(true);
        Label limits=new Label("Betriebssystem-Auslagerung, Absturzabbilder und eigene Kopien oder Bildschirmfotos sind davon nicht erfasst. Dies ist keine Zusage sicherer Speicherloeschung.");limits.setWrapText(true);
        Label budget=new Label(),status=new Label("Bereit – keine Inhalte aus dem Projekt uebernommen.");status.setWrapText(true);status.setId("ephemeralStatus");
        Button send=new Button("Dolphin ausfuehren"),clear=new Button("Ansicht leeren"),close=new Button("Schliessen");
        send.setId("ephemeralSend");clear.setId("ephemeralClear");close.setId("ephemeralClose");
        Runnable updateBudget=()->budget.setText(input.getText().getBytes(StandardCharsets.UTF_8).length+" / "+EphemeralTextClient.availablePromptBytes(language.getValue())+" UTF-8-Bytes");
        input.textProperty().addListener((o,a,b)->updateBudget.run());language.valueProperty().addListener((o,a,b)->updateBudget.run());updateBudget.run();
        Thread[] worker={null};boolean[] open={true};long[] generation={0};
        Runnable clearContent=()-> { generation[0]++; input.setText("");output.setText("");status.setText("Ansicht geleert."); };
        clear.setOnAction(e->clearContent.run());close.setOnAction(e->stage.close());
        send.setDisable(!client.isConfigured());
        if(!client.isConfigured())status.setText("Die direkte Verbindung ohne Speicherung ist noch nicht eingerichtet. Kein gespeicherter Ersatzweg wird verwendet.");
        send.setOnAction(e-> {
            try { EphemeralTextClient.validatePrompt(input.getText(),EphemeralTextClient.system(language.getValue())); }
            catch(Exception error) { status.setText(error.getMessage());return; }
            String prompt=input.getText(),selectedLanguage=language.getValue();long run=++generation[0];
            send.setDisable(true);clear.setDisable(true);language.setDisable(true);input.setEditable(false);output.clear();status.setText("Dolphin arbeitet. Inhalt wird nicht im Projekt gespeichert.");
            worker[0]=Thread.startVirtualThread(()-> {
                try {
                    var result=client.generate(prompt,selectedLanguage);
                    Platform.runLater(()-> { if(open[0] && run==generation[0]) { output.setText(result.text());status.setText("Abgeschlossen. Nur angezeigt. "+result.modelReport()); } });
                } catch(Exception error) {
                    if(error instanceof InterruptedException)Thread.currentThread().interrupt();
                    Platform.runLater(()-> { if(open[0] && run==generation[0])status.setText("Der Auftrag ohne Speicherung konnte nicht abgeschlossen werden. Kein gespeicherter Ersatzauftrag wurde gestartet."); });
                } finally {
                    Platform.runLater(()-> { if(open[0] && run==generation[0]) { worker[0]=null;send.setDisable(false);clear.setDisable(false);language.setDisable(false);input.setEditable(true); } });
                }
            });
        });
        stage.setOnHidden(e-> { open[0]=false;clearContent.run();if(worker[0]!=null)worker[0].interrupt();worker[0]=null; });
        HBox options=new HBox(8,new Label("Ausgabesprache"),language);
        FlowPane buttons=new FlowPane(8,8,send,clear,close);
        VBox root=new VBox(10,notice,limits,options,new Label("Eingabe ohne Speicherung"),input,budget,buttons,new Label("Ausgabe ohne Speicherung"),output,status);
        root.setPadding(new Insets(18));VBox.setVgrow(input,Priority.SOMETIMES);VBox.setVgrow(output,Priority.ALWAYS);
        stage.setScene(new Scene(root,820,760));stage.setMinWidth(600);stage.setMinHeight(580);stage.show();
        return stage;
    }
}
