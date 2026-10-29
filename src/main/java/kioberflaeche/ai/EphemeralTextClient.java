package kioberflaeche.ai;

import kioberflaeche.config.AppConfig;
import kioberflaeche.util.SimpleJson;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** A separate, synchronous content-in-memory route. Never falls back to saved workflows. */
public final class EphemeralTextClient {
    public static final String MODEL_ID="dolphin3-8b";
    public static final int MAX_PROMPT_BYTES=8192-1024-512;
    public record Result(String text,String modelReport) {}
    /** Strict metadata only; no arbitrary server strings or response body are retained. */
    public record Run(String id,String status,Instant created,Instant finished) {}
    private final String endpoint, tokenFile;
    private final HttpClient http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER)
            .proxy(new ProxySelector() {
                @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                @Override public void connectFailed(URI uri,SocketAddress address,IOException error) { }
            }).build();

    public EphemeralTextClient(String endpoint,String tokenFile) {
        this.endpoint=endpoint==null?"":endpoint;
        this.tokenFile=tokenFile==null?"":tokenFile;
    }
    public static EphemeralTextClient configured() {
        return new EphemeralTextClient(AppConfig.ephemeralEndpoint(),AppConfig.ephemeralTokenFile());
    }
    public boolean isConfigured() { return !endpoint.isBlank() && !tokenFile.isBlank(); }

    public Result generate(String prompt,String language) throws IOException,InterruptedException {
        if(!isConfigured()) throw new IOException("Die Verbindung fuer die Anzeige ohne Speicherung ist noch nicht eingerichtet.");
        String system=system(language);
        validatePrompt(prompt,system);
        URI uri=validateEndpoint(endpoint);
        String token=readToken();
        String payload="{\"memory_only\":true,\"modelSelection\":\""+MODEL_ID+"\",\"task\":\"story_write\",\"prompt\":"+quote(prompt)
                +",\"system\":"+quote(system)+",\"numPredict\":1024,\"timeout_seconds\":900}";
        HttpRequest request=HttpRequest.newBuilder(uri).version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofSeconds(930)).header("Authorization","Bearer "+token)
                .header("Content-Type","application/json; charset=utf-8").header("Accept","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload,StandardCharsets.UTF_8)).build();
        try {
            HttpResponse<byte[]> response=boundedResponse(request,256*1024,930);
            if(response.statusCode()!=200) throw new IOException("Der Auftrag ohne Speicherung wurde nicht abgeschlossen.");
            return validateResponse(new String(response.body(),StandardCharsets.UTF_8));
        } catch(InterruptedException error) { throw error; }
        catch(IOException error) {
            // Network exceptions can contain server-controlled text; do not propagate that text.
            throw new IOException("Die Anzeige ohne Speicherung konnte nicht abgeschlossen werden. Es wurde kein gespeicherter Ersatzauftrag gestartet.");
        }
    }
    public List<Run> readRuns() throws IOException,InterruptedException {
        if(!isConfigured()) return List.of();
        URI uri=validateEndpoint(endpoint).resolve("/api/ephemeral-runs");
        HttpRequest request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                .header("Authorization","Bearer "+readToken()).header("Accept","application/json").GET().build();
        try {
            HttpResponse<byte[]> response=boundedResponse(request,64*1024,10);
            if(response.statusCode()!=200) throw new IOException();
            return validateRuns(new String(response.body(),StandardCharsets.UTF_8));
        } catch(InterruptedException error) { throw error; }
        catch(Exception error) { throw new IOException("Dolphin-Status derzeit nicht verfuegbar."); }
    }
    public static List<Run> validateRuns(String raw) throws IOException {
        try {
            var root=SimpleJson.asObject(SimpleJson.parse(raw));
            var privacy=SimpleJson.asObject(root.get("privacy"));
            if(!Boolean.TRUE.equals(root.get("ok")) || !"ephemeral_metadata_only".equals(root.get("scope"))
                    || !Boolean.TRUE.equals(root.get("restartResetsHistory")) || !"memory_only".equals(privacy.get("mode"))
                    || !Boolean.FALSE.equals(privacy.get("applicationContentPersisted"))
                    || !(root.get("runs") instanceof List<?> items) || items.size()>100) throw new IllegalArgumentException();
            List<Run> runs=new ArrayList<>();Set<String> ids=new HashSet<>();
            for(Object item:items) {
                var row=SimpleJson.asObject(item);
                if(!Set.of("id","context","status","created_at","finished_at").containsAll(row.keySet())
                        || !"Dolphinlauf".equals(row.get("context")) || !(row.get("id") instanceof String id)
                        || !id.matches("[0-9a-f]{32}") || !ids.add(id)
                        || !(row.get("status") instanceof String status)
                        || !Set.of("queued","running","completed","failed").contains(status)) throw new IllegalArgumentException();
                Instant created=timestamp(row.get("created_at"));
                Instant finished=row.get("finished_at")==null?null:timestamp(row.get("finished_at"));
                if(finished!=null && finished.isBefore(created)) throw new IllegalArgumentException();
                runs.add(new Run(id,status,created,finished));
            }
            return List.copyOf(runs);
        } catch(Exception error) { throw new IOException("Dolphin-Status konnte nicht geprueft werden."); }
    }
    private static Instant timestamp(Object value) {
        if(!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue()<0 || number.doubleValue()>32503680000L) throw new IllegalArgumentException();
        return Instant.ofEpochMilli(Math.round(number.doubleValue()*1000));
    }
    private HttpResponse<byte[]> boundedResponse(HttpRequest request,int limit,long timeoutSeconds) throws IOException,InterruptedException {
        BoundedBody body=new BoundedBody(limit);
        CompletableFuture<HttpResponse<byte[]>> response=http.sendAsync(request,info->body);
        try { return response.get(timeoutSeconds,TimeUnit.SECONDS); }
        catch(ExecutionException|TimeoutException error) { throw new IOException("Die begrenzte Antwort konnte nicht gelesen werden."); }
        finally { if(!response.isDone())response.cancel(true);body.cancel(); }
    }
    /** Bounds body bytes AND full-body completion time; a headers-only server cannot block the queue. */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private Flow.Subscription subscription;
        BoundedBody(int limit) { this.limit=limit; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public synchronized void onSubscribe(Flow.Subscription value) {
            subscription=value;if(result.isDone())value.cancel();else value.request(1);
        }
        @Override public synchronized void onNext(List<ByteBuffer> buffers) {
            if(result.isDone())return;
            for(ByteBuffer buffer:buffers) {
                if(buffer.remaining()>limit-bytes.size()) { cancel();return; }
                byte[] part=new byte[buffer.remaining()];buffer.get(part);bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        @Override public synchronized void onError(Throwable error) { cancel(); }
        @Override public synchronized void onComplete() { result.complete(bytes.toByteArray());bytes.reset(); }
        synchronized void cancel() {
            if(subscription!=null)subscription.cancel();
            if(!result.isDone())result.completeExceptionally(new IOException("Antwort nicht abgeschlossen."));
            bytes.reset();
        }
    }
    private String readToken() throws IOException {
        try {
            Path path=Path.of(tokenFile);
            if(!Files.isRegularFile(path) || Files.size(path)>1024) throw new IOException();
            String token=Files.readString(path,StandardCharsets.UTF_8).strip();
            if(token.length()<32 || token.length()>512 || !token.chars().allMatch(c->c>=33 && c<=126)) throw new IOException();
            return token;
        } catch(Exception error) { throw new IOException("Die Zugangsdaten fuer die Anzeige ohne Speicherung sind nicht verfuegbar."); }
    }
    public static String system(String language) {
        return "Write the requested text. Follow the user's stated style and task. Return only the final text in "+("Deutsch".equals(language)?"German":"English")+".";
    }
    public static int availablePromptBytes(String language) { return MAX_PROMPT_BYTES-system(language).getBytes(StandardCharsets.UTF_8).length; }
    public static void validatePrompt(String prompt,String system) throws IOException {
        if(prompt==null || prompt.isBlank()) throw new IOException("Bitte einen Textauftrag eingeben.");
        if(prompt.getBytes(StandardCharsets.UTF_8).length+system.getBytes(StandardCharsets.UTF_8).length>MAX_PROMPT_BYTES)
            throw new IOException("Der vollstaendige Textauftrag ist fuer diese begrenzte Teststrecke zu lang. Es wurde nichts abgeschnitten oder gesendet.");
    }
    private static URI validateEndpoint(String endpoint) throws IOException {
        try {
            URI uri=URI.create(endpoint);
            if(!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null
                    || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                    || !uri.getPath().equals("/api/ephemeral-text")) throw new IllegalArgumentException();
            // Deliberately require a local literal IP or localhost: no arbitrary public destinations.
            String host=uri.getHost();
            if(!host.equalsIgnoreCase("localhost") && !host.matches("[0-9.]+") && !host.contains(":")) throw new IllegalArgumentException();
            InetAddress address=InetAddress.getByName(host);
            if(!address.isLoopbackAddress() && !address.isSiteLocalAddress()) throw new IllegalArgumentException();
            return uri;
        } catch(Exception error) { throw new IOException("Die direkte lokale Verbindung fuer die Anzeige ohne Speicherung ist ungueltig."); }
    }
    public static Result validateResponse(String raw) throws IOException {
        try {
            var root=SimpleJson.asObject(SimpleJson.parse(raw));
            var privacy=SimpleJson.asObject(root.get("privacy"));
            var cleanup=SimpleJson.asObject(root.get("cleanup"));
            if(!Boolean.TRUE.equals(root.get("ok")) || root.get("error")!=null
                    || !"memory_only".equals(privacy.get("mode")) || !Boolean.FALSE.equals(privacy.get("applicationContentPersisted"))
                    || !Boolean.TRUE.equals(cleanup.get("verified"))) throw new IllegalArgumentException();
            ModelRouting.verify(root,MODEL_ID);
            if(!(root.get("response") instanceof String text) || text.isBlank()) throw new IllegalArgumentException();
            return new Result(text,ModelRouting.summary(root));
        } catch(Exception error) { throw new IOException("Der Server bestaetigt Modell, Speicheroption oder Abschluss nicht. Die Antwort wird nicht uebernommen."); }
    }
    private static String quote(String text) {
        StringBuilder out=new StringBuilder("\"");
        for(char c:text.toCharArray()) {
            switch(c) { case '"' -> out.append("\\\""); case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n"); case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t");
                default -> { if(c<32) out.append(String.format(Locale.ROOT,"\\u%04x",(int)c)); else out.append(c); } }
        }
        return out.append('"').toString();
    }
}
