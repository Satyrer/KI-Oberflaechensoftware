package kioberflaeche.ai;

import kioberflaeche.util.SimpleJson;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Model IDs are opaque server catalogue keys, never locally invented Ollama tags. */
public final class ModelCatalogClient {
    public record Option(String id, String label, boolean available, List<String> tasks, String reason) {
        public static Option automatic() { return new Option("auto", "Automatisch (nach Aufgabe)", true, List.of(), "Der Server waehlt je Aufgabe; die Entscheidung erscheint am Ergebnis."); }
        public boolean supports(String task) { return id.equals("auto") || (task.equals("auto") ? tasks.stream().anyMatch(List.of("chat","coding","guard_test")::contains) : tasks.contains(task)); }
        @Override public String toString() { return label + (available ? "" : " – nicht verfuegbar"); }
    }
    private final String endpoint;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(8)).build();
    public ModelCatalogClient(String endpoint) { this.endpoint=endpoint == null ? "" : endpoint; }
    public static ModelCatalogClient workflow(String baseUrl) {
        return new ModelCatalogClient(baseUrl == null || baseUrl.isBlank() ? "" : baseUrl.replaceAll("/+$", "")+"/webhook/schreib-ai/models");
    }
    public List<Option> load() throws IOException, InterruptedException {
        if(endpoint.isBlank()) throw new IOException("Kein Modellkatalog eingerichtet.");
        var response=http.send(HttpRequest.newBuilder(URI.create(endpoint)).version(HttpClient.Version.HTTP_1_1).timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if(response.statusCode()!=200) throw new IOException("Modellkatalog nicht erreichbar (HTTP "+response.statusCode()+").");
        return parse(response.body());
    }
    public static List<Option> parse(String json) throws IOException {
        try {
            Map<String,Object> root=SimpleJson.asObject(SimpleJson.parse(json));
            if(Boolean.FALSE.equals(root.get("ok")) || !(root.get("models") instanceof List<?>)) throw new IllegalArgumentException();
            List<Option> options=new ArrayList<>(); options.add(Option.automatic()); Set<String> ids=new HashSet<>(); ids.add("auto");
            for(Object item:SimpleJson.asArray(root.get("models"))) {
                var model=SimpleJson.asObject(item); String id=SimpleJson.asString(model.get("id"));
                if(id.isBlank() || !ids.add(id)) throw new IllegalArgumentException();
                String label=SimpleJson.asString(model.get("label"));
                List<String> tasks=SimpleJson.asArray(model.get("tasks")).stream().map(SimpleJson::asString).toList();
                options.add(new Option(id,label.isBlank()?id:label,Boolean.TRUE.equals(model.get("available")),tasks,SimpleJson.asString(model.get("reason"))));
            }
            return List.copyOf(options);
        } catch(RuntimeException error) { throw new IOException("Der Modellkatalog ist nicht lesbar.",error); }
    }
}
