import com.sun.net.httpserver.HttpServer;
import kioberflaeche.agent.AgentClient;
import kioberflaeche.agent.AgentJson;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;

/** Synthetic loopback fixture; never reads credentials, repositories, or document data. */
public final class AgentFixture implements AutoCloseable {
    static final String TOKEN = "fixture-project-token-0000000000000000";
    static final String OPERATOR = "fixture-operator-token-11111111111111";
    static final String ID = "a".repeat(32), REVIEW = "b".repeat(64), TRANSACTION = "c".repeat(32);
    static final String DIFF = "--- a/example.py\n+++ b/example.py\n@@ -1,2 +1,2 @@\n def add(a, b):\n-    return a - b\n+    return a + b\n";
    final HttpServer server;
    final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    final AtomicInteger starts = new AtomicInteger(), polls = new AtomicInteger(), applies = new AtomicInteger(), calls = new AtomicInteger();
    final List<String> submitted = new CopyOnWriteArrayList<>();
    final List<String> authorizations = new CopyOnWriteArrayList<>(), paths = new CopyOnWriteArrayList<>();
    volatile String status = "running", fault = "", phaseOverride = "";
    volatile Map<String,Object> qualitySummary;
    volatile Map<String,Object> diagnostics;
    volatile Map<String,Object> actionUsage;
    volatile boolean queuePaused = true, failQueueAfterCommit, queueConflict;
    volatile String queueError = "", blockedEntry = "";
    volatile Object queueOverride;
    final List<Map<String,Object>> waiting = new ArrayList<>(), active = new ArrayList<>();
    final Set<String> queueKeys = new HashSet<>();
    final List<String> queueBodies = new CopyOnWriteArrayList<>();
    final AtomicInteger queueAdds = new AtomicInteger(), queueMutations = new AtomicInteger();
    volatile long modelSeconds=1800, usedMilliseconds=125000, remainingMilliseconds=1675000, reservedMilliseconds;
    volatile boolean firstStartFails;
    volatile long delayMillis;
    volatile CountDownLatch reached = new CountDownLatch(1);
    final byte[] png;
    AgentFixture() throws Exception {
        BufferedImage image = new BufferedImage(560, 220, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(new Color(244,247,252)); g.fillRect(0,0,560,220);
        g.setColor(new Color(23,42,67)); g.setFont(new Font("SansSerif",Font.BOLD,17));
        g.drawString("Synthetische UML-Testgrafik",26,30);
        g.drawRect(30,65,200,105); g.drawRect(330,65,200,105);
        g.drawString("a : Rechner",48,91); g.drawString("b : Ergebnis",348,91);
        g.drawLine(30,104,230,104); g.drawLine(330,104,530,104);
        g.setFont(new Font("SansSerif",Font.PLAIN,15)); g.drawString("wert = 2",48,135); g.drawString("wert = 5",348,135);
        g.drawLine(230,116,330,116); g.drawLine(320,110,330,116); g.drawLine(320,122,330,116); g.dispose();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ImageIO.write(image,"png",bytes); png=bytes.toByteArray();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.setExecutor(pool); server.createContext("/",exchange -> {
            calls.incrementAndGet();
            String path=exchange.getRequestURI().getPath();
            String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            String auth=exchange.getRequestHeaders().getFirst("Authorization");
            authorizations.add(auth == null ? "" : auth); paths.add(path); reached.countDown();
            try {
                if(delayMillis>0) Thread.sleep(delayMillis);
                Object response; int code=200;
                if(path.endsWith("/models")) response=Map.of("ok",true,"models",List.of(Map.of("id","test-coder","label","Testmodell","available",true,"tasks",List.of("coding"))));
                else if(path.endsWith("/projects")) response=Map.of("ok",true,"projects",List.of(Map.of("projectId","fixture","profile","python","models",List.of("auto","test-coder"),"capabilities",Map.of("uml",true,"apply",true),"qualityProfile","omp01-v1")));
                else if(path.contains("/queue")) {
                    if (queueConflict && path.endsWith("/start-next")) {
                        queueConflict=false;queuePaused=true;queueError="project_source_changed";
                        synchronized(this) { blockedEntry=(String)waiting.getFirst().get("entryId"); }
                        code=409;response=Map.of("ok",false,"error",Map.of("code",queueError));
                    } else {
                        response = queueResponse(path, body);
                        if(path.endsWith("/add")) code=202;
                    }
                    if(!path.endsWith("/queue") && failQueueAfterCommit) {
                        failQueueAfterCommit=false; code=503;response=Map.of("ok",false,"error",Map.of("code","fixture_unavailable"));
                    }
                }
                else if(path.endsWith("/jobs")) {
                    starts.incrementAndGet(); submitted.add(body);
                    code=202; response=jobRoot();
                    if(firstStartFails && starts.get()==1) { code=503; response=Map.of("ok",false,"error",Map.of("code","fixture_unavailable")); }
                } else if(path.endsWith("/review")) response=reviewRoot();
                else if(path.contains("/diagrams/")) response=Map.of("ok",true,"diagram",diagram(true));
                else if(path.endsWith("/apply")) {
                    applies.incrementAndGet();
                    if(!("Bearer "+OPERATOR).equals(auth)) throw new AssertionError("wrong apply credential");
                    status="completed";
                    response=Map.of("ok",true,"job",job(),"adoption",Map.of("status","applied","reviewId",REVIEW,"transactionId",TRANSACTION));
                } else if(path.endsWith("/cancel")) { status="cancelled";code=202;response=jobRoot(); }
                else if(path.endsWith("/resume")) { status="running";code=202;response=jobRoot(); }
                else { polls.incrementAndGet();response=jobRoot(); }
                String raw=AgentJson.encode(response);
                switch(fault) {
                    case "duplicate" -> raw="{\"ok\":true,\"ok\":false}";
                    case "large" -> raw="{\"ok\":true,\"padding\":\""+"x".repeat(AgentClient.MAX_RESPONSE+1)+"\"}";
                    case "redirect" -> { code=302; exchange.getResponseHeaders().set("Location","http://127.0.0.1:1/leak"); }
                    case "error" -> {code=403;raw=AgentJson.encode(Map.of("ok",false,"error",Map.of("code","forbidden","detail",TOKEN)));}
                    case "wrong-job" -> raw=raw.replace(ID,"d".repeat(32));
                    case "wrong-hash" -> raw=raw.replace(AgentClient.sha256(DIFF.getBytes(StandardCharsets.UTF_8)),"0".repeat(64));
                    case "invalid-png" -> raw=raw.replace(Base64.getEncoder().encodeToString(png),"AAAA");
                    case "foreign-path" -> raw=raw.replace("example.py","../../secrets");
                    case "wrong-project" -> raw=raw.replace("\"projectId\":\"fixture\"","\"projectId\":\"foreign\"");
                    case "context" -> {
                        if (!path.endsWith("/cancel")) { code=409;raw=AgentJson.encode(Map.of("ok",false,"error",Map.of("code","context_release_revoked"))); }
                    }
                    case "apply-not-completed" -> raw=raw.replace("\"status\":\"completed\"","\"status\":\"awaiting_review\"");
                    case "apply-cancelled" -> raw=raw.replace("\"cancelRequested\":false","\"cancelRequested\":true");
                }
                byte[] data=raw.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
                exchange.sendResponseHeaders(code,data.length); exchange.getResponseBody().write(data);
            } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        }); server.start();
    }
    String base() { return "http://127.0.0.1:"+server.getAddress().getPort(); }
    Map<String,Object> job() {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("jobId",ID);result.put("projectId","fixture");result.put("status",status);result.put("phase",phaseOverride.isEmpty()?(status.equals("queued")?null:"review_ready"):phaseOverride);
        result.put("errorCode",null);result.put("cancelRequested",false);result.put("modelSelection","auto");
        result.put("modelSeconds",modelSeconds);result.put("modelMillisecondsUsed",usedMilliseconds);result.put("modelMillisecondsRemaining",remainingMilliseconds);result.put("modelMillisecondsReserved",reservedMilliseconds);
        result.put("actionBudget",10);result.put("actionsUsed",1);result.put("reviewAvailable",Set.of("awaiting_review","completed").contains(status));
        result.put("statusPath","https://untrusted.invalid/do-not-follow");
        if (qualitySummary != null) result.put("quality", qualitySummary);
        if (diagnostics != null) result.put("diagnostics", diagnostics);
        if (actionUsage != null) result.putAll(actionUsage);
        return result;
    }
    synchronized Object queueResponse(String path, String body) throws IOException {
        if (!path.endsWith("/queue")) {
            queueBodies.add(body);
            Map<?,?> value=(Map<?,?>)AgentJson.parse(body);
            String key=path+":"+value.getOrDefault("requestKey",null);
            if(queueKeys.add(key)) {
                queueMutations.incrementAndGet();
                if(path.endsWith("/add")) {
                    int index=queueAdds.incrementAndGet();
                    waiting.add(new LinkedHashMap<>(Map.of("entryId",String.format("%032x",index),"jobId",index==1?ID:String.format("%032x",index+100),
                            "projectId","fixture","status","waiting","jobStatus","held","taskPreview",value.get("task"),
                            "modelSelection",value.get("modelSelection"),"createdAt",new java.math.BigDecimal("1792800000.125"),"updatedAt",new java.math.BigDecimal("1792800000.125"))));
                } else if(path.endsWith("/remove")) {
                    String id=path.substring(path.lastIndexOf("/queue/")+7,path.length()-7);
                    waiting.removeIf(e -> e.get("entryId").equals(id));
                } else if(path.endsWith("/clear")) waiting.clear();
                else if(path.endsWith("/pause")) queuePaused=true;
                else if(path.endsWith("/resume")) queuePaused=false;
                else if(path.endsWith("/start-next") && !waiting.isEmpty() && active.isEmpty()) {
                    var entry=waiting.removeFirst();entry.put("status","released");entry.put("jobStatus","running");active.add(entry);
                }
            }
        }
        Map<String,Object> value = new LinkedHashMap<>(Map.of("projectId","fixture","paused",queuePaused,"waitingCount",waiting.size(),"items",List.copyOf(waiting),"activeItems",List.copyOf(active)));
        value.put("lastError",queueError.isEmpty()?null:queueError);value.put("blockedEntryId",blockedEntry.isEmpty()?null:blockedEntry);
        Object queue=queueOverride==null?value:queueOverride;
        return Map.of("ok",true,"queue",queue);
    }
    Map<String,Object> jobRoot() { return Map.of("ok",true,"job",job()); }
    Map<String,Object> reviewRoot() {
        Map<String,Object> review=new LinkedHashMap<>();
        review.put("projectId","fixture");review.put("reviewId",REVIEW);review.put("diff",DIFF);
        review.put("diffSha256",AgentClient.sha256(DIFF.getBytes(StandardCharsets.UTF_8)));
        review.put("checks",List.of(Map.of("name","Verhaltenstest add","status","passed","exit_code",0)));
        review.put("operations",List.of(Map.of("path","example.py","kind","modify")));
        review.put("warnings",List.of("Isolierte Testdaten. Keine Nutzerdateien beteiligt."));
        review.put("approvalGranted",status.equals("completed"));review.put("diagrams",List.of(diagram(false)));
        review.put("adoption",Map.of("available",!status.equals("completed"),"confirmation",status.equals("completed")?"":"APPROVE "+REVIEW));
        if (qualitySummary != null) review.put("quality", qualitySummary);
        if (fault.equals("quality-review-mismatch")) review.put("quality", quality("failed"));
        return Map.of("ok",true,"job",job(),"review",review);
    }
    static Map<String,Object> quality(String status) {
        return Map.of("profile", "omp01-v1", "status", status, "acceptance", status.equals("passed") ? "bounded_profile_passed" : "not_accepted",
                "failedChecks", status.equals("passed") ? List.of() : List.of("java.required-methods"),
                "runnerStatus", status, "behaviorTestsExecuted", status.equals("passed"), "reportSha256", "e".repeat(64));
    }
    static Map<String,Object> diagnostics() {
        Map<String,Object> stages = new LinkedHashMap<>();
        for (String key : List.of("sources", "action_schema", "rendering", "proposal", "compilation", "behavior_tests", "task_acceptance", "adoption")) {
            stages.put(key, Map.of("status", "not_executed"));
        }
        stages.put("sources", Map.of("status", "partial", "sha256", "a".repeat(64), "required_count", 4, "read_count", 2, "context_bound_count", 2));
        stages.put("rendering", Map.of("status", "failed", "attempt_count", 2, "artifact_count", 0));
        stages.put("proposal", Map.of("status", "failed", "rejection_count", 1, "raw_response", "PRIVATE-DRAFT-MUST-NOT-BE-SHOWN"));
        return Map.of("schema_version", "agent-stage-overview-v1", "stages", stages, "approval_granted", false, "model_claims_used_as_evidence", false);
    }
    Map<String,Object> diagram(boolean image) {
        Map<String,Object> result=new LinkedHashMap<>(Map.of("id","objektmodell","type","object","width",560,"height",220,"pngSha256",AgentClient.sha256(png)));
        if(image) result.put("pngBase64",Base64.getEncoder().encodeToString(png));
        return result;
    }
    AgentClient.StartRequest request() { return new AgentClient.StartRequest("fixture","Repariere die Addition.",UUID.randomUUID().toString(),"auto",List.of(),true,List.of(new AgentClient.RequiredDiagram("objektmodell","object")),1800,10,2); }
    @Override public void close() { server.stop(0);pool.shutdownNow(); }
}
