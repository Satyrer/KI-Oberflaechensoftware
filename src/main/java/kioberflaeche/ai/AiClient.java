package kioberflaeche.ai;

import kioberflaeche.model.ChatMessage;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface AiClient {
    CompletableFuture<String> ask(String sessionId, List<ChatMessage> history, String userMessage);

    default CompletableFuture<String> ask(List<ChatMessage> history, String userMessage) {
        return ask("default", history, userMessage);
    }
}
