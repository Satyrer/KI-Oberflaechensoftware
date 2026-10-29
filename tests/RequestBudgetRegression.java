import kioberflaeche.ai.*;
import kioberflaeche.config.AppConfig;
import kioberflaeche.media.TranscriptionService;
import kioberflaeche.story.SchreibAiWorkflowClient;
import javax.net.ssl.*;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Captures the actual outgoing HttpRequest deadlines; never waits for hours or contacts a server. */
public class RequestBudgetRegression {
    static int checks;
    static void check(boolean value,String message) { if(!value) throw new AssertionError(message); checks++; }
    static void capture(Object client,String field,CaptureHttp transport) throws Exception {
        var member=client.getClass().getDeclaredField(field); member.setAccessible(true); member.set(client,transport);
    }
    static long seconds(CaptureHttp transport) { return transport.request.timeout().orElseThrow().toSeconds(); }
    static class CaptureHttp extends HttpClient {
        HttpRequest request; String body="{\"ok\":true,\"response\":\"Testantwort\",\"projectId\":\"Budget\",\"file_changes\":[],\"projects\":[]}";
        @SuppressWarnings("unchecked") public <T> HttpResponse<T> send(HttpRequest request,HttpResponse.BodyHandler<T> handler) {
            this.request=request; final T value=(T)body;
            return new HttpResponse<>() {
                public int statusCode(){return 200;} public HttpRequest request(){return request;}
                public Optional<HttpResponse<T>> previousResponse(){return Optional.empty();}
                public HttpHeaders headers(){return HttpHeaders.of(Map.of("Content-Type",List.of("application/json")),(a,b)->true);}
                public T body(){return value;} public Optional<SSLSession> sslSession(){return Optional.empty();}
                public URI uri(){return request.uri();} public Version version(){return Version.HTTP_1_1;}
            };
        }
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r,HttpResponse.BodyHandler<T> h){return CompletableFuture.completedFuture(send(r,h));}
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r,HttpResponse.BodyHandler<T> h,HttpResponse.PushPromiseHandler<T> p){return sendAsync(r,h);}
        public Optional<CookieHandler> cookieHandler(){return Optional.empty();}
        public Optional<Duration> connectTimeout(){return Optional.empty();}
        public Redirect followRedirects(){return Redirect.NEVER;}
        public Optional<ProxySelector> proxy(){return Optional.empty();}
        public SSLContext sslContext(){try{return SSLContext.getDefault();}catch(Exception e){throw new RuntimeException(e);}}
        public SSLParameters sslParameters(){return new SSLParameters();}
        public Optional<Authenticator> authenticator(){return Optional.empty();}
        public Version version(){return Version.HTTP_1_1;}
        public Optional<Executor> executor(){return Optional.empty();}
    }
    static AppConfig config(int seconds) {
        return new AppConfig("http://127.0.0.1/api/chat","ollama","","",300,"unused","","","",90,
                "http://127.0.0.1",seconds,"","","ffmpeg","","http://127.0.0.1/transcribe","","de");
    }
    public static void main(String[] args) throws Exception {
        Path run=Files.createTempDirectory(Path.of(args[0]),"request-budget-");
        for(int configured:List.of(300,3600)) {
            var transport=new CaptureHttp();
            var direct=new HttpAiClient("http://127.0.0.1/api/chat","","",configured); capture(direct,"client",transport);
            direct.askWithModel("test",List.of(),"Test","auto","auto").join();
            check(seconds(transport)==Math.max(configured,2760),"actual direct chat HTTP deadline respects reasoning floor and larger config");
            check(transport.request.version().orElseThrow()==HttpClient.Version.HTTP_1_1,"direct chat HTTP/1.1 retained");
            var n8n=new N8nChatWebhookClient("http://127.0.0.1/chat","",configured); capture(n8n,"client",transport);
            n8n.askWithModel("test",List.of(),"Test","auto","auto").join();
            check(seconds(transport)==Math.max(configured,2760),"actual n8n chat HTTP deadline respects reasoning floor and larger config");
            check(seconds(transport)>2700 && seconds(transport)>2100+455,"outer chat covers n8n and sequential retrieval plus model time");
        }
        for(int configured:List.of(240,54000)) {
            var transport=new CaptureHttp(); var story=new SchreibAiWorkflowClient("http://127.0.0.1",configured); capture(story,"client",transport);
            story.buildContext("Budget","Deutsch","Test",List.of(),List.of());
            check(seconds(transport)==Math.max(configured,43500),"context story 12h plus5min retained");
            story.writeScene("Budget","Deutsch","Test","manuscript/test.md",List.of(),List.of());
            check(seconds(transport)==Math.max(configured,43500) && seconds(transport)>43320,"scene deadline exceeds n8n 12h plus2min");
            story.listProjects(); check(seconds(transport)==configured,"non-model project metadata timeout not globally widened");
        }
        Path audio=run.resolve("synthetic.wav"); Files.write(audio,new byte[]{1,2,3});
        for(int configured:List.of(240,50000)) for(String model:List.of("small","large-v3")) for(String mode:List.of("plain","structured")) {
            var transport=new CaptureHttp(); transport.body="{\"ok\":true,\"text\":\"Neutrales Testtranskript.\",\"asr\":{\"model\":\""+model+"\"},\"ai\":{\"skipped\":true}}";
            var transcription=new TranscriptionService(config(configured)); capture(transcription,"httpClient",transport);
            Path output=run.resolve(configured+"-"+model+"-"+mode+(mode.equals("plain")?".txt":".json"));
            transcription.transcribe(audio,output,"de",model,mode,"","","","auto");
            long minimum=mode.equals("structured")?45600:43500;
            check(seconds(transport)==Math.max(configured,minimum),"actual "+model+"/"+mode+" HTTP timeout covers full workflow and keeps larger config");
            check(seconds(transport)>43200+(mode.equals("structured")?2100:0),"sequential ASR and optional structure call have response reserve");
            check(Files.isRegularFile(output),"captured deadline path retains successful transcription write");
        }
        check(RequestBudgets.transcription(1,"STRUCTURED").toSeconds()==45600,"case-insensitive structured deadline");
        System.out.println("PASS: "+checks+" actual HTTP-request budget checks: chat2760s, Story43500s, plain ASR43500s, structured ASR45600s; larger configuration retained; no network/model generation.");
    }
}
