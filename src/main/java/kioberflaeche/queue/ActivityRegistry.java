package kioberflaeche.queue;

import java.time.Instant;
import java.util.*;

/** Observes existing work; it never schedules, retries or cancels an operation. */
public final class ActivityRegistry {
    public enum State {
        RUNNING("In Bearbeitung"), WAITING("Wartend"), REVIEW("Pruefung offen"),
        UNKNOWN("Status unklar"), FAILED("Fehlgeschlagen"), CANCELLED("Abgebrochen"), DONE("Abgeschlossen");
        private final String label;
        State(String label) { this.label = label; }
        public boolean terminal() { return this == FAILED || this == CANCELLED || this == DONE; }
        @Override public String toString() { return label; }
    }
    public record Entry(String id, String area, String title, State state, String phase,
                        Instant created, Instant updated, Instant finished) {}

    private static final ActivityRegistry SHARED = new ActivityRegistry();
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    public static ActivityRegistry shared() { return SHARED; }

    public Handle waiting(String area, String title, String phase) {
        String id = UUID.randomUUID().toString();
        update(id, area, title, State.WAITING, phase);
        return new Handle(id, area, title);
    }
    public Handle start(String area, String title, String phase) {
        Handle handle = waiting(area, title, phase);
        handle.running(phase);
        return handle;
    }
    public synchronized void update(String id, String area, String title, State state, String phase) {
        Instant now = Instant.now();
        Entry previous = entries.get(id);
        if(previous==null)JobNumbers.forId("local:"+id);
        Instant created = previous == null ? now : previous.created();
        Instant finished = state.terminal() ? previous != null && previous.state() == state
                ? previous.finished() : now : null;
        entries.put(id, new Entry(id, area, title, state, phase, created, now, finished));
        long completed = entries.values().stream().filter(e -> e.state().terminal()).count();
        var iterator = entries.values().iterator();
        while (completed > 200 && iterator.hasNext()) {
            if (iterator.next().state().terminal()) { iterator.remove(); completed--; }
        }
    }
    public synchronized List<Entry> snapshot() {
        return entries.values().stream().sorted(Comparator.comparing(Entry::state)
                .thenComparing(Entry::created)).toList();
    }
    public synchronized void clearFinished() { entries.values().removeIf(e -> e.state().terminal()); }

    public final class Handle {
        private final String id, area, title;
        private Handle(String id, String area, String title) { this.id = id; this.area = area; this.title = title; }
        public void state(State state, String phase) { update(id, area, title, state, phase); }
        public void running(String phase) { state(State.RUNNING, phase); }
        public void done(String phase) { state(State.DONE, phase); }
        public void failed(String phase) { state(State.FAILED, phase); }
        public void cancelled(String phase) { state(State.CANCELLED, phase); }
    }
}
