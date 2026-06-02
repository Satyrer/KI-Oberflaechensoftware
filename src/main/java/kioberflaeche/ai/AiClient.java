package kioberflaeche.ai;

import kioberflaeche.model.ChatMessage;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface AiClient {
    CompletableFuture<String> ask(List<ChatMessage> history, String userMessage);
}
