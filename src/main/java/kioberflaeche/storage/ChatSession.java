package kioberflaeche.storage;

import kioberflaeche.model.ChatMessage;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class ChatSession {
    private final String id;
    private String title;
    private final List<ChatMessage> messages;

    public ChatSession(String id, String title) {
        this(id, title, new ArrayList<>());
    }

    public ChatSession(String id, String title, List<ChatMessage> messages) {
        this.id = id;
        this.title = title;
        this.messages = new ArrayList<>(messages);
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public void title(String title) {
        this.title = title;
    }

    public List<ChatMessage> messages() {
        return List.copyOf(messages);
    }

    public void add(ChatMessage.Sender sender, String text) {
        messages.add(new ChatMessage(sender, text, LocalDateTime.now()));
        if ("Neuer Chat".equals(title) && sender == ChatMessage.Sender.USER && !text.isBlank()) {
            title = text.length() > 34 ? text.substring(0, 34) + "..." : text;
        }
    }

    public void addLoaded(ChatMessage message) {
        messages.add(message);
    }
}
