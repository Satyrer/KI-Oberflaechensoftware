package kioberflaeche.ai;

import kioberflaeche.model.ChatMessage;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface AiClient {
    record Reply(String text, String modelReport) {}
    CompletableFuture<String> ask(String sessionId, List<ChatMessage> history, String userMessage);

    default CompletableFuture<Reply> askWithModel(String sessionId, List<ChatMessage> history, String userMessage,
                                                  String modelSelection, String task) {
        if(!ModelRouting.normalize(modelSelection).equals("auto")) return CompletableFuture.failedFuture(
                new IllegalArgumentException("Dieser Anschluss unterstuetzt keine verbindliche Modellwahl."));
        return ask(sessionId,history,userMessage).thenApply(text->new Reply(text,"Modell vom Server nicht bestaetigt."));
    }

    /** Three completed exchanges; system diagnostics and unfinished user turns never become model context. */
    static List<ChatMessage> recentHistory(List<ChatMessage> history) {
        java.util.ArrayList<ChatMessage> pairs=new java.util.ArrayList<>(); ChatMessage user=null;
        for(var message:history) {
            if(message.sender()==ChatMessage.Sender.USER) user=message;
            else if(message.sender()==ChatMessage.Sender.ASSISTANT && user!=null) { pairs.add(user); pairs.add(message); user=null; }
        }
        return List.copyOf(pairs.subList(Math.max(0,pairs.size()-6),pairs.size()));
    }

    default CompletableFuture<String> ask(List<ChatMessage> history, String userMessage) {
        return ask("default", history, userMessage);
    }
}
