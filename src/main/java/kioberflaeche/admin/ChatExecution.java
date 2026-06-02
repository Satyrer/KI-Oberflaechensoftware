package kioberflaeche.admin;

public record ChatExecution(
        String id,
        String started,
        String status,
        String workflow,
        String preview
) {
    public String displayTitle() {
        String title = started == null || started.isBlank() ? id : started;
        if (status != null && !status.isBlank()) {
            title += " [" + status + "]";
        }
        return title;
    }
}
