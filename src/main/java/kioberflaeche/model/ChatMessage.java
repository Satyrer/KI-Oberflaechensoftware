package kioberflaeche.model;

import java.time.LocalDateTime;

public record ChatMessage(Sender sender, String text, LocalDateTime timestamp) {
    public enum Sender {
        USER("user"),
        ASSISTANT("assistant"),
        SYSTEM("system");

        private final String apiRole;

        Sender(String apiRole) {
            this.apiRole = apiRole;
        }

        public String apiRole() {
            return apiRole;
        }
    }
}
