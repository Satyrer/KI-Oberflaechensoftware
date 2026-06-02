package kioberflaeche.ui;

import kioberflaeche.ai.AiClient;
import kioberflaeche.model.ChatMessage;
import kioberflaeche.storage.ChatSession;
import kioberflaeche.storage.ChatStore;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class ChatWindow extends JFrame {
    private static final Color WHATSAPP_GREEN = new Color(0, 128, 105);
    private static final Color CHAT_BACKGROUND = new Color(234, 230, 220);
    private static final Color USER_BUBBLE = new Color(220, 248, 198);
    private static final Color ASSISTANT_BUBBLE = Color.WHITE;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final AiClient aiClient;
    private final ChatStore chatStore;
    private final DefaultListModel<ChatSession> sessions = new DefaultListModel<>();
    private final JList<ChatSession> chatList = new JList<>(sessions);
    private final JPanel messagesPanel = new JPanel(new GridBagLayout());
    private final JScrollPane messagesScrollPane = new JScrollPane(messagesPanel);
    private final JTextArea inputArea = new JTextArea(3, 40);
    private final JButton sendButton = new JButton("Senden");
    private final JLabel titleLabel = new JLabel("KI Chat");

    private ChatSession activeSession;

    public ChatWindow(AiClient aiClient, ChatStore chatStore) {
        this.aiClient = aiClient;
        this.chatStore = chatStore;

        setTitle("KI Oberflaechensoftware");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(980, 680));
        setLocationByPlatform(true);

        buildLayout();
        loadSessions();
    }

    private void buildLayout() {
        JPanel root = new JPanel(new BorderLayout());
        root.add(buildSidebar(), BorderLayout.WEST);
        root.add(buildChatArea(), BorderLayout.CENTER);
        setContentPane(root);
    }

    private JPanel buildSidebar() {
        JPanel sidebar = new JPanel(new BorderLayout());
        sidebar.setPreferredSize(new Dimension(260, 0));
        sidebar.setBackground(new Color(248, 248, 248));

        JButton newChatButton = new JButton("Neuer Chat");
        newChatButton.addActionListener(event -> startNewChat());

        JPanel header = new JPanel(new BorderLayout());
        header.setBorder(new EmptyBorder(12, 12, 12, 12));
        header.setBackground(new Color(240, 242, 245));
        header.add(new JLabel("Chats"), BorderLayout.WEST);
        header.add(newChatButton, BorderLayout.EAST);

        chatList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        chatList.setCellRenderer((list, value, index, isSelected, cellHasFocus) -> {
            JLabel label = new JLabel(value.title());
            label.setOpaque(true);
            label.setBorder(new EmptyBorder(12, 14, 12, 14));
            label.setBackground(isSelected ? new Color(220, 238, 234) : Color.WHITE);
            return label;
        });
        chatList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && chatList.getSelectedValue() != null) {
                activeSession = chatList.getSelectedValue();
                renderMessages();
            }
        });

        sidebar.add(header, BorderLayout.NORTH);
        sidebar.add(new JScrollPane(chatList), BorderLayout.CENTER);
        return sidebar;
    }

    private JPanel buildChatArea() {
        JPanel chatArea = new JPanel(new BorderLayout());

        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(WHATSAPP_GREEN);
        header.setBorder(new EmptyBorder(14, 18, 14, 18));
        titleLabel.setForeground(Color.WHITE);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 18f));
        header.add(titleLabel, BorderLayout.WEST);

        messagesPanel.setBackground(CHAT_BACKGROUND);
        messagesScrollPane.setBorder(BorderFactory.createEmptyBorder());
        messagesScrollPane.getVerticalScrollBar().setUnitIncrement(16);

        JPanel composer = new JPanel(new BorderLayout(10, 0));
        composer.setBorder(new EmptyBorder(12, 12, 12, 12));
        composer.setBackground(new Color(240, 242, 245));

        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);
        inputArea.setBorder(new EmptyBorder(10, 12, 10, 12));
        inputArea.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.VK_ENTER && !event.isShiftDown()) {
                    event.consume();
                    sendMessage();
                }
            }
        });

        sendButton.setBackground(WHATSAPP_GREEN);
        sendButton.setForeground(Color.WHITE);
        sendButton.addActionListener(event -> sendMessage());

        composer.add(new JScrollPane(inputArea), BorderLayout.CENTER);
        composer.add(sendButton, BorderLayout.EAST);

        chatArea.add(header, BorderLayout.NORTH);
        chatArea.add(messagesScrollPane, BorderLayout.CENTER);
        chatArea.add(composer, BorderLayout.SOUTH);
        return chatArea;
    }

    private void loadSessions() {
        try {
            List<ChatSession> loaded = chatStore.loadAll();
            loaded.forEach(sessions::addElement);
            if (sessions.isEmpty()) {
                startNewChat();
            } else {
                chatList.setSelectedIndex(0);
            }
        } catch (IOException | IllegalStateException e) {
            showError("Chats konnten nicht geladen werden", e);
            startNewChat();
        }
    }

    private void startNewChat() {
        ChatSession session = chatStore.newSession();
        sessions.add(0, session);
        chatList.setSelectedIndex(0);
        activeSession = session;
        renderMessages();
        saveActiveSession();
    }

    private void sendMessage() {
        if (activeSession == null) {
            startNewChat();
        }

        String userText = inputArea.getText().trim();
        if (userText.isBlank()) {
            return;
        }

        inputArea.setText("");
        activeSession.add(ChatMessage.Sender.USER, userText);
        chatList.repaint();
        renderMessages();
        saveActiveSession();
        setSendingState(true);

        List<ChatMessage> historyBeforeAnswer = activeSession.messages();
        aiClient.ask(historyBeforeAnswer, userText)
                .thenAccept(answer -> SwingUtilities.invokeLater(() -> {
                    activeSession.add(ChatMessage.Sender.ASSISTANT, answer);
                    renderMessages();
                    saveActiveSession();
                    setSendingState(false);
                }))
                .exceptionally(error -> {
                    SwingUtilities.invokeLater(() -> {
                        activeSession.add(ChatMessage.Sender.SYSTEM, "Fehler bei der KI-Anbindung: " + error.getMessage());
                        renderMessages();
                        saveActiveSession();
                        setSendingState(false);
                    });
                    return null;
                });
    }

    private void setSendingState(boolean sending) {
        sendButton.setEnabled(!sending);
        inputArea.setEnabled(!sending);
        sendButton.setText(sending ? "Warte..." : "Senden");
    }

    private void renderMessages() {
        messagesPanel.removeAll();
        titleLabel.setText(activeSession == null ? "KI Chat" : activeSession.title());

        if (activeSession != null) {
            List<ChatMessage> messages = activeSession.messages();
            for (int i = 0; i < messages.size(); i++) {
                addMessageBubble(messages.get(i), i);
            }
        }

        GridBagConstraints spacer = new GridBagConstraints();
        spacer.gridx = 0;
        spacer.gridy = GridBagConstraints.RELATIVE;
        spacer.weighty = 1;
        spacer.fill = GridBagConstraints.VERTICAL;
        messagesPanel.add(new JPanel(), spacer);

        messagesPanel.revalidate();
        messagesPanel.repaint();
        SwingUtilities.invokeLater(() -> messagesScrollPane.getVerticalScrollBar()
                .setValue(messagesScrollPane.getVerticalScrollBar().getMaximum()));
    }

    private void addMessageBubble(ChatMessage message, int row) {
        boolean fromUser = message.sender() == ChatMessage.Sender.USER;
        boolean system = message.sender() == ChatMessage.Sender.SYSTEM;

        JTextArea text = new JTextArea(message.text() + "\n" + TIME_FORMAT.format(message.timestamp()));
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setOpaque(false);
        text.setFont(text.getFont().deriveFont(14f));
        text.setBorder(new EmptyBorder(10, 12, 10, 12));

        JPanel bubble = new JPanel(new BorderLayout());
        bubble.setBackground(system ? new Color(255, 245, 190) : fromUser ? USER_BUBBLE : ASSISTANT_BUBBLE);
        bubble.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0, 0, 0, 20), 1),
                new EmptyBorder(0, 0, 0, 0)
        ));
        bubble.add(text, BorderLayout.CENTER);
        bubble.setMaximumSize(new Dimension(560, Integer.MAX_VALUE));

        JPanel line = new JPanel(new FlowLayout(fromUser ? FlowLayout.RIGHT : FlowLayout.LEFT, 14, 4));
        line.setOpaque(false);
        line.add(bubble);

        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = row;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets = new Insets(4, 10, 4, 10);
        messagesPanel.add(line, constraints);
    }

    private void saveActiveSession() {
        if (activeSession == null) {
            return;
        }

        try {
            chatStore.save(activeSession);
        } catch (IOException e) {
            showError("Chat konnte nicht gespeichert werden", e);
        }
    }

    private void showError(String title, Exception exception) {
        JOptionPane.showMessageDialog(
                this,
                title + ":\n" + exception.getMessage(),
                "Fehler",
                JOptionPane.ERROR_MESSAGE
        );
    }
}
