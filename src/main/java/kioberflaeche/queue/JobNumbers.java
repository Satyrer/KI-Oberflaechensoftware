package kioberflaeche.queue;

import java.util.HashMap;
import java.util.Map;

/** Shared display references for this application process; never written to disk. */
public final class JobNumbers {
    private static final Map<String,Long> NUMBERS=new HashMap<>();
    private static long next=1;
    private JobNumbers() {}
    public static synchronized long forId(String namespacedId) {
        if(namespacedId==null || namespacedId.isBlank())throw new IllegalArgumentException("Auftragskennung fehlt");
        return NUMBERS.computeIfAbsent(namespacedId,ignored->next++);
    }
}
