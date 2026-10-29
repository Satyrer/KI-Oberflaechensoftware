package kioberflaeche.queue;

import kioberflaeche.agent.AgentClient;
import java.util.*;

/** Joins queue admission and job polling by server job ID, not by UI selection. */
public final class AgentActivity {
    private static final Map<String, String> TITLES = new HashMap<>();
    private AgentActivity() {}
    private static String key(String project, String job) { return "agent:" + project + ":" + job; }
    public static synchronized Set<String> queue(AgentClient.TaskQueue queue) {
        Set<String> present = new HashSet<>();
        var all = new ArrayList<>(queue.activeItems()); all.addAll(queue.items());
        for (var entry : all) {
            String id = key(queue.projectId(), entry.jobId());
            present.add(id); TITLES.put(id, entry.taskPreview());
            ActivityRegistry.shared().update(id, "Agent", entry.taskPreview(), state(entry.waiting() ? "held" : entry.jobStatus()),
                    queue.projectId() + " - " + (entry.waiting() && queue.paused() ? "Warteliste pausiert" : entry.jobStatus()));
        }
        return present;
    }
    public static synchronized void job(AgentClient.Job job) {
        String id = key(job.projectId(), job.jobId());
        ActivityRegistry.shared().update(id, "Agent", TITLES.getOrDefault(id, job.projectId() + " - " + job.jobId()),
                state(job.status()), job.phase().isBlank() ? job.status() : job.phase());
    }
    public static void unavailable(Set<String> ids, String message) {
        for (var entry : ActivityRegistry.shared().snapshot()) {
            if (ids.contains(entry.id()) && !entry.state().terminal() && entry.state() != ActivityRegistry.State.REVIEW) {
                ActivityRegistry.shared().update(entry.id(), entry.area(), entry.title(), ActivityRegistry.State.UNKNOWN, message);
            }
        }
    }
    public static void unavailable(AgentClient.Job job, String message) {
        if (job != null) unavailable(Set.of(key(job.projectId(), job.jobId())), message);
    }
    private static ActivityRegistry.State state(String status) {
        return switch (status) {
            case "held", "queued" -> ActivityRegistry.State.WAITING;
            case "running", "awaiting_test", "cancel_requested" -> ActivityRegistry.State.RUNNING;
            case "awaiting_review", "recovery_required" -> ActivityRegistry.State.REVIEW;
            case "completed" -> ActivityRegistry.State.DONE;
            case "failed", "exhausted" -> ActivityRegistry.State.FAILED;
            case "cancelled" -> ActivityRegistry.State.CANCELLED;
            default -> ActivityRegistry.State.UNKNOWN;
        };
    }
}
