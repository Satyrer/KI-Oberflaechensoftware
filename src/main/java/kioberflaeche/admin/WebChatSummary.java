package kioberflaeche.admin;

public record WebChatSummary(
        String id,
        String title,
        String lastMessageAt,
        String type,
        String agentName
) {
    public String displayTitle() {
        String displayTitle = title == null || title.isBlank() ? id : title;
        String suffix = lastMessageAt == null || lastMessageAt.isBlank() ? "" : " | " + lastMessageAt;
        return displayTitle + suffix;
    }

    public String preview() {
        return "ID: " + id
                + "\nTitel: " + title
                + "\nTyp: " + type
                + "\nAgent: " + agentName
                + "\nLetzte Nachricht: " + lastMessageAt;
    }
}
