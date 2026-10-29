package kioberflaeche.ai;

import kioberflaeche.util.SimpleJson;
import java.io.IOException;
import java.util.*;

/** Reports server evidence, and rejects a silent substitution of an explicit selection. */
public final class ModelRouting {
    private ModelRouting() {}
    public static String normalize(String value) { return value==null || value.isBlank()?"auto":value.strip(); }
    public static String summary(String raw) {
        try { return summary(SimpleJson.asObject(SimpleJson.parse(raw))); } catch(RuntimeException ignored) { return "Modell vom Server nicht bestaetigt."; }
    }
    public static String summary(Map<String,Object> root) {
        List<Map<String,Object>> routes=routes(root); Set<String> lines=new LinkedHashSet<>();
        for(var route:routes) {
            String selected=SimpleJson.asString(route.get("selected"));
            String model=SimpleJson.asString(route.get("model"));
            if(selected.isBlank() && model.isBlank()) continue;
            String task=SimpleJson.asString(route.get("task")), reason=SimpleJson.asString(route.get("reason"));
            lines.add((task.isBlank()?"Modell":taskLabel(task))+": "+(model.isBlank()?selected:model)+(reason.isBlank()?"":" — "+reason));
        }
        if(lines.isEmpty() && Boolean.TRUE.equals(SimpleJson.asObject(root.get("ai")).get("skipped"))) return "Kein Textmodell aufgerufen; die Strukturierung wurde ausgelassen.";
        return lines.isEmpty()?"Modell vom Server nicht bestaetigt.":String.join("\n",lines);
    }
    public static void verify(String raw, String selected) throws IOException {
        try { verify(SimpleJson.asObject(SimpleJson.parse(raw)),selected); }
        catch(RuntimeException error) { if(!normalize(selected).equals("auto")) throw new IOException("Der Server bestaetigt die gewaehlte Modell-ID nicht."); }
    }
    public static void verify(Map<String,Object> root, String selected) throws IOException {
        String status=SimpleJson.asString(root.get("status"));
        if(Boolean.FALSE.equals(root.get("ok")) || root.get("error")!=null || status.equals("error") || status.startsWith("blocked"))
            throw new IOException("Der Server meldet einen fehlgeschlagenen oder blockierten Modellauftrag. Es wurde keine Antwort uebernommen.");
        selected=normalize(selected); if(selected.equals("auto")) return;
        var routes=routes(root);
        if(routes.isEmpty()) throw new IOException("Der Server bestaetigt die gewaehlte Modell-ID nicht. Ergebnis wurde nicht uebernommen.");
        for(var route:routes) {
            if(!selected.equals(SimpleJson.asString(route.get("selected")))) throw new IOException("Der Server hat ein anderes Modell verwendet als ausgewaehlt. Ergebnis wurde nicht uebernommen.");
        }
    }
    private static List<Map<String,Object>> routes(Map<String,Object> root) {
        List<Map<String,Object>> result=new ArrayList<>();
        add(result,root.get("modelRouting"));
        var ai=SimpleJson.asObject(root.get("ai")); add(result,ai.get("modelRouting"));
        for(Object call:SimpleJson.asArray(SimpleJson.asObject(root.get("analysis")).get("calls"))) add(result,SimpleJson.asObject(call).get("modelRouting"));
        return result;
    }
    private static void add(List<Map<String,Object>> result,Object value) { if(value instanceof Map<?,?> map && !map.isEmpty()) result.add(SimpleJson.asObject(value)); }
    private static String taskLabel(String task) { return switch(task) {
        case "coding" -> "Programmierung / Fehlersuche"; case "story_context" -> "Kontextauswertung";
        case "story_write" -> "Szenentext"; case "story_review" -> "Ergebnispruefung";
        case "guard_test" -> "Guard-Untersuchung"; case "transcription_structure" -> "Transkriptstruktur";
        case "chat" -> "Gespraech"; default -> task;
    }; }
}
