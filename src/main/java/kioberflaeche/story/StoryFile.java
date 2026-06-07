package kioberflaeche.story;

public record StoryFile(
        String path,
        String layer,
        String name,
        long size,
        String modified
) {
    public String displayTitle() {
        return path == null || path.isBlank() ? name : path;
    }
}
