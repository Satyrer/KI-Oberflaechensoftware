package kioberflaeche.ui;

import javafx.application.Platform;
import javafx.scene.control.*;
import kioberflaeche.ai.ModelCatalogClient;
import kioberflaeche.ai.ModelPreferences;
import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;

/** Refresh never silently replaces an explicit choice, including a now-unavailable model. */
public final class ModelPicker {
    private final ComboBox<ModelCatalogClient.Option> combo;
    private final Label hint;
    private final Button refresh;
    private final Supplier<String> task;
    private List<ModelCatalogClient.Option> options=List.of(ModelCatalogClient.Option.automatic());
    private boolean loaded, loading, locked, updating;
    private String catalogueError="", preferenceError="", preferenceKey;
    public ModelPicker(ComboBox<ModelCatalogClient.Option> combo, Label hint, Button refresh, Supplier<String> task) {
        this.combo=combo; this.hint=hint; this.refresh=refresh; this.task=task;
        refresh.setText("Liste laden");
        refresh.setTooltip(new Tooltip("Aktualisiert nur die Modellliste. Es wird dabei kein KI-Modell in den Arbeitsspeicher geladen."));
        combo.setTooltip(new Tooltip("Die Auswahl gilt fuer den naechsten Auftrag. Das Modell wird erst bei der Ausfuehrung geladen."));
        combo.getItems().setAll(options); combo.getSelectionModel().selectFirst();
        combo.valueProperty().addListener((o,a,b)-> {
            if(!updating && b!=null && preferenceKey!=null) {
                try { ModelPreferences.put(preferenceKey,b.id()); preferenceError=""; }
                catch(IOException error) { preferenceError="Auswahl gilt jetzt, konnte aber nicht fuer den naechsten Start gespeichert werden."; }
            }
            describe();
        }); describe();
    }
    public void usePreference(String key) {
        preferenceKey=key;
        try { restoreSelection(ModelPreferences.get(key,"auto")); preferenceError=""; }
        catch(IOException error) { preferenceError="Gespeicherte Modellauswahl konnte nicht gelesen werden. Auswahl vor dem Auftrag pruefen."; }
        describe();
    }
    private void restoreSelection(String id) {
        updating=true;
        try { combo.setValue(options.stream().filter(option->option.id().equals(id)).findFirst().orElse(
                new ModelCatalogClient.Option(id,id,false,List.of(),"Gespeicherte Auswahl; Verfuegbarkeit noch nicht bestaetigt."))); }
        finally { updating=false; }
        refilter();
    }
    public void configure(ModelCatalogClient client) { refresh.setOnAction(e->refresh(client)); refresh(client); }
    public void refresh(ModelCatalogClient client) {
        if(loading || locked) return; loading=true; refresh.setDisable(true); hint.setText("Pruefe verfuegbare Modelle …");
        Thread.startVirtualThread(()-> { try {
            var values=client.load(); Platform.runLater(()-> { options=values; loaded=true; loading=false; catalogueError=""; refilter(); refresh.setDisable(locked); });
        } catch(Exception error) { Platform.runLater(()-> { loaded=false; loading=false; refresh.setDisable(locked); catalogueError="Modellkatalog nicht erreichbar. Automatische Wahl bleibt serverseitig; manuelle Auswahl erst nach Aktualisierung."; describe(); }); } });
    }
    public void refilter() {
        var current=combo.getValue(); var filtered=new ArrayList<>(options.stream().filter(o->o.supports(task.get())).toList());
        if(current!=null && filtered.stream().noneMatch(o->o.id().equals(current.id()))) filtered.add(new ModelCatalogClient.Option(current.id(),current.label(),false,List.of(),"Fuer diese Aufgabe nicht verfuegbar. Auswahl bitte bewusst aendern."));
        updating=true;
        try { combo.getItems().setAll(filtered);
            combo.setValue(filtered.stream().filter(o->current!=null && o.id().equals(current.id())).findFirst().orElse(ModelCatalogClient.Option.automatic())); }
        finally { updating=false; }
        describe();
    }
    private void describe() {
        if(!catalogueError.isBlank() || !preferenceError.isBlank()) { hint.setText(String.join(" ",catalogueError,preferenceError).strip()); return; }
        var value=combo.getValue(); if(value==null) return;
        hint.setText(value.reason().isBlank()?(value.available()?"Dieses Modell wird fuer den Auftrag verbindlich angefordert.":"Dieses Modell ist derzeit nicht verfuegbar."):value.reason());
    }
    public String selection() throws IOException {
        var selected=combo.getValue();
        if(selected==null) throw new IOException("Bitte ein Modell auswaehlen.");
        if(!selected.id().equals("auto") && (!loaded || !selected.available() || !selected.supports(task.get()))) throw new IOException("Das ausgewaehlte Modell ist fuer diesen Auftrag nicht verfuegbar. Katalog aktualisieren oder Auswahl bewusst aendern.");
        return selected.id();
    }
    public void setLocked(boolean value) { locked=value; combo.setDisable(value); refresh.setDisable(value || loading); }
}
