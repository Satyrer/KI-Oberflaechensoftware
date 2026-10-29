import kioberflaeche.agent.AgentClient;
import kioberflaeche.agent.AgentJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class AgentClientRegression {
    static int checks;
    interface Throwing { void run() throws Exception; }
    static void check(boolean ok,String text) { if(!ok) throw new AssertionError(text); checks++; }
    static void reject(Throwing work,String text) throws Exception {
        try { work.run(); throw new AssertionError("Expected rejection: "+text); }
        catch(IOException|IllegalArgumentException expected) { checks++; }
    }
    public static void main(String[] args) throws Exception {
        for(String invalid:List.of("{\"a\":1,\"a\":2}","{\"a\":1,\"\\u0061\":2}","01","1.","1e","1.٢","[true,]","{\"a\":1,}","\"\\ud800\"","\"\\udc00\"","[1] false","[".repeat(34)+"]".repeat(34))) {
            reject(()->AgentJson.parse(invalid),"strict JSON");
        }
        String sample=AgentJson.encode(Map.of("text","Änderung\n💡","values",List.of(1,true)));
        check(AgentJson.parse(sample) instanceof Map,"unicode JSON roundtrip");
        for(String url:List.of("file:///tmp","http://user:secret@localhost","http://localhost/?token=secret","http://localhost/#x","http://localhost/%2e","http://localhost/..")) {
            reject(()->new AgentClient(url,AgentFixture.TOKEN),"invalid origin");
        }
        reject(()->new AgentClient("http://127.0.0.1","tiny"),"short credential");
        reject(()->new AgentClient("http://127.0.0.1",AgentFixture.TOKEN+"\r\nX: bad"),"header injection");
        try(AgentFixture fixture=new AgentFixture()) {
            AgentClient client=new AgentClient(fixture.base(),AgentFixture.TOKEN);
            check(client.projects().getFirst().uml(),"project capabilities");
            check(client.projects().getFirst().qualityProfile().equals("omp01-v1"),"explicit server quality profile");
            check(!client.toString().contains(AgentFixture.TOKEN),"credential hidden");
            fixture.status="queued";
            check(client.start(fixture.request()).phase().isEmpty(),"initial null phase");
            fixture.status="running";
            check(client.status(AgentFixture.ID).active(),"running status");
            check(client.status(AgentFixture.ID).quality()==null,"legacy response never invents task acceptance");
            check(client.status(AgentFixture.ID).diagnostics()==null,"older jobs tolerate missing diagnostics");
            fixture.diagnostics=AgentFixture.diagnostics();
            var diagnostics=client.status(AgentFixture.ID).diagnostics();
            check(diagnostics.stages().size()==8,"all eight diagnostic stages preserved");
            check(diagnostics.stages().getFirst().counts().get("read_count")==2L,"bounded source counts");
            check(diagnostics.stages().get(4).status().equals("not_executed"),"failed Stage A does not invent failed compilation");
            check(!diagnostics.toString().contains("PRIVATE-DRAFT"),"unknown diagnostic model text discarded");
            var invalidDiagnostics=new HashMap<>(AgentFixture.diagnostics());
            invalidDiagnostics.put("approval_granted",true);fixture.diagnostics=invalidDiagnostics;
            reject(()->client.status(AgentFixture.ID),"diagnostics cannot grant adoption");
            invalidDiagnostics=new HashMap<>(AgentFixture.diagnostics());
            var invalidStages=new HashMap<>((Map<String,Object>)invalidDiagnostics.get("stages"));
            invalidStages.put("sources",Map.of("status","passed","sha256","unbound"));
            invalidDiagnostics.put("stages",invalidStages);fixture.diagnostics=invalidDiagnostics;
            reject(()->client.status(AgentFixture.ID),"diagnostic evidence hashes must be valid");
            invalidStages.put("sources",Map.of("status","all_good"));
            reject(()->client.status(AgentFixture.ID),"unknown stage status rejected");
            invalidStages.remove("compilation");
            invalidStages.put("sources",Map.of("status","not_executed"));
            reject(()->client.status(AgentFixture.ID),"incomplete diagnostics rejected without fabricated stages");
            fixture.diagnostics=null;
            fixture.qualitySummary=AgentFixture.quality("failed");
            check(client.status(AgentFixture.ID).quality().failedChecks().equals(List.of("java.required-methods")),"failed quality shown without D review");
            check(!client.status(AgentFixture.ID).quality().behaviorTestsExecuted(),"unexecuted behavior tests preserved");
            var invalidQuality=new HashMap<>(fixture.qualitySummary);
            invalidQuality.put("acceptance","bounded_profile_passed");fixture.qualitySummary=invalidQuality;
            reject(()->client.status(AgentFixture.ID),"failed quality cannot claim acceptance");
            invalidQuality=new HashMap<>(AgentFixture.quality("passed"));invalidQuality.put("failedChecks",List.of("missing"));fixture.qualitySummary=invalidQuality;
            reject(()->client.status(AgentFixture.ID),"passed quality contradicting failed checks");
            invalidQuality=new HashMap<>(AgentFixture.quality("passed"));invalidQuality.put("reportSha256","unbound");fixture.qualitySummary=invalidQuality;
            reject(()->client.status(AgentFixture.ID),"quality report identity required");
            fixture.qualitySummary=null;
            check(client.cancel(AgentFixture.ID).status().equals("cancelled"),"cancel endpoint");
            fixture.status="recovery_required";
            check(client.resume(AgentFixture.ID).active(),"resume endpoint");
            fixture.status="awaiting_review";
            var review=client.review(AgentFixture.ID);
            check(review.diff().equals(AgentFixture.DIFF),"exact diff");
            check(review.checks().getFirst().exitCode()==0 && review.operations().size()==1,"checks and operations");
            fixture.qualitySummary=AgentFixture.quality("passed");
            check(client.review(AgentFixture.ID).quality().acceptance().equals("bounded_profile_passed"),"bounded quality acceptance in review");
            fixture.fault="quality-review-mismatch";
            reject(()->client.review(AgentFixture.ID),"review and job quality identities must agree");
            fixture.fault="";fixture.qualitySummary=null;
            check(Arrays.equals(client.diagram(AgentFixture.ID,review.diagrams().getFirst()).png(),fixture.png),"verified PNG");
            reject(()->client.apply(review,AgentFixture.OPERATOR,"Tester","APPROVE other"),"bind confirmation");
            check(fixture.applies.get()==0,"no automatic adoption");
            var adopted=client.apply(review,AgentFixture.OPERATOR,"Tester",review.confirmation());
            check(adopted.transactionId().equals(AgentFixture.TRANSACTION),"adoption receipt");
            check(!client.review(AgentFixture.ID).adoptionAvailable(),"completed review remains readable without apply");
            check(fixture.paths.stream().allMatch(p->p.startsWith("/webhook/schreib-ai/agent/")),"server status URLs ignored");
            check(fixture.authorizations.stream().filter(a->a.equals("Bearer "+AgentFixture.OPERATOR)).count()==1,"separate operator credential");
            for(String fault:List.of("apply-not-completed","apply-cancelled")) {
                fixture.fault=fault;
                reject(()->client.apply(review,AgentFixture.OPERATOR,"Tester",review.confirmation()),"inconsistent adoption "+fault);
            }
            fixture.fault="";
            int before=fixture.calls.get();
            for(String id:List.of("../secrets","a".repeat(32)+"/review","A".repeat(32),"https://foreign.invalid")) reject(()->client.status(id),"route validation");
            check(fixture.calls.get()==before,"invalid routes never requested");
            for(String fault:List.of("duplicate","large","redirect","wrong-job")) {
                fixture.fault=fault; reject(()->client.status(AgentFixture.ID),fault);
            }
            fixture.fault="error";
            try { client.projects(); throw new AssertionError(); }
            catch(AgentClient.Failure failure) { check(failure.status()==403 && !failure.getMessage().contains(AgentFixture.TOKEN),"server error details hidden"); }
            fixture.status="awaiting_review";
            fixture.fault="wrong-hash";reject(()->client.review(AgentFixture.ID),"diff identity");
            fixture.fault="foreign-path";reject(()->client.review(AgentFixture.ID),"operation path");
            fixture.fault="invalid-png";reject(()->client.diagram(AgentFixture.ID,review.diagrams().getFirst()),"PNG corruption");
            fixture.fault="";
            var base=fixture.request();
            var reference=new AgentClient.DocumentReference("e".repeat(32),7,"f".repeat(64));
            var request=new AgentClient.StartRequest(base.projectId(),base.task(),base.requestKey(),base.modelSelection(),
                    List.of(reference),true,base.requiredDiagrams(),1800,3,1);
            check(AgentJson.encode(AgentClient.startBody(request)).contains("\"revision\":7"),"context revision preserved");
            reject(()->AgentClient.startBody(new AgentClient.StartRequest("fixture","x",base.requestKey(),"auto",List.of(reference,reference),false,List.of(),1800,3,1)),"duplicate context");
            reject(()->AgentClient.startBody(new AgentClient.StartRequest("fixture","x",base.requestKey(),"auto",List.of(),false,base.requiredDiagrams(),1800,3,1)),"required UML without tool");
            reject(()->AgentClient.startBody(new AgentClient.StartRequest("fixture","x",base.requestKey(),"auto",List.of(),false,List.of(),90,16,1)),"action budget");
            check(AgentClient.startBody(new AgentClient.StartRequest("fixture","x",base.requestKey(),"auto",List.of(),false,List.of(),90,15,1)).get("actions").equals(15), "fifteen normal actions accepted");
            reject(()->AgentClient.startBody(new AgentClient.StartRequest("fixture"," ",base.requestKey(),"auto",List.of(),false,List.of(),1800,3,1)),"empty task");
        }
        System.out.println("PASS: "+checks+" client checks; strict bounded JSON, fixed routes, credential isolation, review/diff/PNG binding and adoption.");
    }
}
