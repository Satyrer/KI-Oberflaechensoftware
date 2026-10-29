package kioberflaeche.ai;

import java.time.Duration;

/** Outer HTTP deadlines cover the complete sequential workflow, including CPU reasoning. */
public final class RequestBudgets {
    private RequestBudgets() {}
    // n8n chat allows 2700 s: routing/retrieval (up to 455 s), model call (2100 s), reserve.
    public static final long CHAT_SECONDS = 2760;
    // The same n8n Whisper request allows 12 h for small and large-v3.
    public static final long ASR_SECONDS = 12 * 60 * 60;
    public static final long WORKFLOW_RESERVE_SECONDS = 300;
    public static final long STRUCTURE_SECONDS = 2100;

    public static Duration chat(int configuredSeconds) {
        return Duration.ofSeconds(Math.max(configuredSeconds, CHAT_SECONDS));
    }

    public static Duration transcription(int configuredSeconds, String outputMode) {
        long minimum=ASR_SECONDS+WORKFLOW_RESERVE_SECONDS;
        if("structured".equalsIgnoreCase(outputMode)) minimum+=STRUCTURE_SECONDS;
        return Duration.ofSeconds(Math.max(configuredSeconds,minimum));
    }
}
