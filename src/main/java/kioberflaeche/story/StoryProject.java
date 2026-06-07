package kioberflaeche.story;

public record StoryProject(String id, String name, boolean template) {
    public String displayTitle() {
        return template ? name + " (Beispiel)" : name;
    }

    @Override
    public String toString() {
        return displayTitle();
    }
}
