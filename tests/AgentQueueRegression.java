import kioberflaeche.agent.AgentClient;
import java.util.*;

/** Synthetic HTTP contract checks; no guest, model, project files or credentials. */
public class AgentQueueRegression extends AgentClientRegression {
    public static void main(String[] args) throws Exception {
        try (AgentFixture fixture = new AgentFixture()) {
            AgentClient client = new AgentClient(fixture.base(), AgentFixture.TOKEN);
            check(client.queue("fixture").paused(), "new queue starts paused");
            var first = fixture.request();
            fixture.failQueueAfterCommit = true;
            reject(() -> client.enqueue(first), "ambiguous add response");
            check(client.enqueue(first).waitingCount() == 1 && fixture.queueAdds.get() == 1, "identical add retry does not duplicate");
            check(fixture.queueBodies.get(0).equals(fixture.queueBodies.get(1)), "add key and complete package stable");
            String key = UUID.randomUUID().toString();
            var started = client.queueCommand("fixture", "start-next", key);
            check(started.paused() && started.items().isEmpty() && started.activeItems().size() == 1, "single release leaves queue paused");
            client.queueCommand("fixture", "start-next", key);
            check(fixture.queueMutations.get() == 2, "single release retry is one mutation");
            check(client.enqueue(fixture.request()).waitingCount() == 1, "add while active");
            var queue = client.queue("fixture");
            String entry = queue.items().getFirst().entryId();
            check(client.removeQueued("fixture", entry).waitingCount() == 0, "specific waiting entry removed");
            check(client.queue("fixture").activeItems().size() == 1, "remove preserves active item");
            client.enqueue(fixture.request());
            key = UUID.randomUUID().toString();
            client.queueCommand("fixture", "clear", key);
            client.enqueue(fixture.request());
            check(client.queueCommand("fixture", "clear", key).waitingCount() == 1, "clear retry preserves later addition");
            check(!client.queueCommand("fixture", "resume", UUID.randomUUID().toString()).paused(), "queue resume route");
            check(client.queueCommand("fixture", "pause", UUID.randomUUID().toString()).paused(), "queue pause route");
            int before = fixture.calls.get();
            reject(() -> client.removeQueued("fixture", "../jobs"), "invalid entry route");
            reject(() -> client.queueCommand("fixture", "delete-everything", "key"), "operation allowlist");
            reject(() -> client.queueCommand("fixture", "clear", "a\nb"), "request key syntax");
            check(fixture.calls.get() == before, "invalid routes never sent");
            fixture.fault = "wrong-project";
            reject(() -> client.queue("fixture"), "queue belongs to authenticated project");
            fixture.fault = "";
            Map<String,Object> base = new LinkedHashMap<>(Map.of("projectId","fixture","paused",true,"waitingCount",0,"items",List.of(),"activeItems",List.of()));
            fixture.queueOverride = new LinkedHashMap<>(base); ((Map<String,Object>)fixture.queueOverride).put("waitingCount", 1);
            reject(() -> client.queue("fixture"), "contradictory waiting count");
            var invalidEntry = new LinkedHashMap<>(fixture.waiting.getFirst()); invalidEntry.put("projectId","foreign");
            fixture.queueOverride = Map.of("projectId","fixture","paused",true,"waitingCount",1,"items",List.of(invalidEntry),"activeItems",List.of());
            reject(() -> client.queue("fixture"), "entry project boundary");
            fixture.queueOverride = Map.of("projectId","fixture","paused",true,"waitingCount",2,"items",List.of(fixture.waiting.getFirst(),fixture.waiting.getFirst()),"activeItems",List.of());
            reject(() -> client.queue("fixture"), "duplicate queue identity");
            fixture.queueOverride = null;
            var blocked = new LinkedHashMap<>(base);
            blocked.put("items",List.of(fixture.waiting.getFirst()));blocked.put("waitingCount",1);
            blocked.put("lastError","project_source_changed");blocked.put("blockedEntryId",fixture.waiting.getFirst().get("entryId"));
            fixture.queueOverride = blocked;
            check(client.queue("fixture").lastError().equals("project_source_changed"), "bounded admission error visible");
            blocked.put("blockedEntryId","f".repeat(32));
            reject(() -> client.queue("fixture"), "blocked entry must be waiting in same snapshot");
            blocked.put("blockedEntryId",fixture.waiting.getFirst().get("entryId"));blocked.put("lastError","raw private error text");
            reject(() -> client.queue("fixture"), "arbitrary admission error text rejected");
            fixture.queueOverride = null;
            fixture.status = "held";
            check(!client.status(AgentFixture.ID).active(), "held job cannot resume via running controls");
            fixture.status = "running";
            fixture.actionUsage = new LinkedHashMap<>(Map.of("budgetPolicy","normal-plus-uml-v1","normalActionBudget",10,"normalActionsUsed",9,
                    "totalActionBudget",13,"totalActionsUsed",12,"actionsUsed",12,"umlAttemptBudget",3,
                    "umlAttempts",List.of(Map.of("diagramId","overview","diagramType","class","attemptsUsed",3,"attemptBudget",3))));
            var usage = client.status(AgentFixture.ID).actionUsage();
            check(usage.normalUsed()==9 && usage.totalUsed()==12 && usage.diagrams().getFirst().attemptsUsed()==3, "normal and UML counts separately preserved above ten total");
            fixture.actionUsage.put("totalActionsUsed",13);
            reject(() -> client.status(AgentFixture.ID), "contradictory legacy/total action counts");
            fixture.actionUsage.put("totalActionsUsed",12);fixture.actionUsage.put("totalActionBudget",23);
            reject(() -> client.status(AgentFixture.ID), "bounded expanded budget");
            fixture.actionUsage = Map.of("budgetPolicy","normal-plus-uml-v1","normalActionBudget",10,"normalActionsUsed",1,
                    "totalActionBudget",22,"totalActionsUsed",1,"actionsUsed",1,"umlAttemptBudget",3,"umlAttempts",List.of());
            check(client.status(AgentFixture.ID).actionUsage().totalBudget()==22, "optional UML has four reserved slots before IDs exist");
            fixture.actionUsage = Map.of("budgetPolicy","normal-plus-uml-v1","normalActionBudget",15,"normalActionsUsed",15,
                    "actionBudget",15,"totalActionBudget",27,"totalActionsUsed",15,"actionsUsed",15,"umlAttemptBudget",3,"umlAttempts",List.of());
            check(client.status(AgentFixture.ID).actionUsage().normalBudget()==15, "new normal budget accepts fifteen without granting historical jobs credit");
            fixture.actionUsage = null;
            check(client.status(AgentFixture.ID).actionUsage()==null, "legacy budget remains explicitly unknown");
            check(fixture.applies.get()==0 && fixture.starts.get()==0, "queue actions never apply or direct-start");
        }
        System.out.println("PASS: " + checks + " queue/budget client checks.");
    }
}
