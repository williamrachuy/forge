package forge.screens.match;

import forge.Singletons;
import forge.ai.llm.UltronAdvisor;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.gamemodes.net.ChatMessage;
import forge.gui.FThreads;
import forge.gui.framework.SDisplayUtil;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.toolbox.FLabel;
import forge.toolbox.FScrollPane;
import forge.toolbox.FSkin;
import forge.toolbox.FTextField;
import forge.toolbox.SmartScroller;
import forge.view.FDialog;
import forge.view.FFrame;
import net.miginfocom.swing.MigLayout;
import org.apache.commons.lang3.StringUtils;

import javax.swing.JTextPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.Color;
import java.awt.Point;
import java.awt.Rectangle;

final class UltronChatDialog {
    private static final int DEFAULT_WIDTH = 460;
    private static final int DEFAULT_HEIGHT = 240;

    private final CMatchUI matchUI;
    private final FDialog window = new FDialog(false, true, "0");
    private final JTextPane txtLog = new JTextPane();
    private final StyledDocument doc;
    private final SimpleAttributeSet systemStyle = new SimpleAttributeSet();
    private final SimpleAttributeSet playerStyle = new SimpleAttributeSet();
    private final SimpleAttributeSet ultronStyle = new SimpleAttributeSet();
    private final FTextField txtInput = new FTextField.Builder().maxLength(500).build();
    private final FLabel cmdSend = new FLabel.ButtonBuilder().text("Send").build();
    private boolean positioned;

    UltronChatDialog(CMatchUI matchUI) {
        this.matchUI = matchUI;
        this.doc = txtLog.getStyledDocument();
        initializeStyles();
        initializeWindow();
        syncUltronSpeechPreference();
        UltronAdvisor.get().addTableTalkListener(this::addUltronTableTalk);
    }

    void show() {
        if (!positioned) {
            positionWindow();
            positioned = true;
        }
        Game game = getGame();
        if (game == null) {
            addSystemMessage("No active game is available.");
        } else if (!UltronAdvisor.get().hasUltronPlayer(game)) {
            addSystemMessage("No Ultron player is present in this game.");
        }
        window.setDefaultFocus(txtInput);
        window.setVisible(true);
    }

    private void initializeStyles() {
        StyleConstants.setForeground(systemStyle, new Color(100, 150, 255));
        StyleConstants.setForeground(ultronStyle, new Color(230, 160, 50));
        FSkin.SkinColor skinTextColor = FSkin.getColor(FSkin.Colors.CLR_TEXT);
        StyleConstants.setForeground(playerStyle, skinTextColor == null ? Color.WHITE : skinTextColor.getColor());
    }

    private void initializeWindow() {
        window.setTitle("Chat with Ultron");
        window.setBackground(FSkin.getColor(FSkin.Colors.CLR_ZEBRA));
        window.setBorder(new FSkin.LineSkinBorder(FSkin.getColor(FSkin.Colors.CLR_BORDERS)));
        window.setLayout(new MigLayout("insets 0, gap 0, ax center, wrap 2"));

        txtLog.setOpaque(true);
        txtLog.setFocusable(true);
        txtLog.setEditable(false);
        FSkin.SkinColor skinZebraColor = FSkin.getColor(FSkin.Colors.CLR_ZEBRA);
        txtLog.setBackground(skinZebraColor == null ? Color.DARK_GRAY : skinZebraColor.getColor());

        FScrollPane scroller = new FScrollPane(txtLog, false);
        scroller.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
        new SmartScroller(scroller).attach();
        window.add(scroller, "pushx, hmin 24, pushy, growy, growx, gap 2px 2px 2px 0, sx 2");
        window.add(txtInput, "pushx, growx, h 26px!, gap 2px 2px 2px 0");
        window.add(cmdSend, "w 60px!, h 28px!, gap 0 0 2px 0");

        txtInput.addActionListener(e -> sendMessage());
        cmdSend.setCommand((Runnable) this::sendMessage);
    }

    private void positionWindow() {
        FFrame mainFrame = Singletons.getView().getFrame();
        int w = Math.max(DEFAULT_WIDTH, (int)(mainFrame.getWidth() * 0.30f));
        int h = DEFAULT_HEIGHT;
        int x = mainFrame.getX() + mainFrame.getWidth() - w - 24;
        int y = mainFrame.getY() + mainFrame.getHeight() - h - 64;
        Rectangle bounds = SDisplayUtil.getScreenBoundsForPoint(new Point(x + w / 2, y + h / 2));
        x = Math.max(bounds.x, Math.min(x, bounds.x + bounds.width - w));
        y = Math.max(bounds.y, Math.min(y, bounds.y + bounds.height - h));
        window.setBounds(x, y, w, h);
    }

    private void sendMessage() {
        String message = txtInput.getText();
        txtInput.setText("");
        if (StringUtils.isBlank(message)) {
            return;
        }

        Game game = getGame();
        if (game == null) {
            addSystemMessage("No active game is available.");
            return;
        }

        Player speaker = getSpeaker(game);
        String speakerName = speaker == null ? "Player" : speaker.getName();
        addMessage(new ChatMessage(speakerName, message));
        addSystemMessage("Ultron is thinking...");

        FThreads.invokeInBackgroundThread(() -> {
            syncUltronSpeechPreference();
            UltronAdvisor.ChatResponse response = UltronAdvisor.get().chat(game, speaker, message);
            FThreads.invokeInEdtLater(() -> {
                if (response.success()) {
                    addMessage(new ChatMessage("Ultron", response.message()));
                } else {
                    addSystemMessage("Ultron unavailable: " + response.error());
                }
            });
        });
    }

    private static void syncUltronSpeechPreference() {
        UltronAdvisor.get().setSpeechPreferenceEnabled(FModel.getPreferences().getPrefBoolean(FPref.ULTRON_ENABLE_SPEECH));
    }

    private Game getGame() {
        return matchUI.getUltronChatGame();
    }

    private Player getSpeaker(Game game) {
        PlayerView current = matchUI.getCurrentPlayer();
        if (current != null && matchUI.isLocalPlayer(current)) {
            return game.getPlayer(current);
        }
        for (PlayerView playerView : matchUI.getLocalPlayers()) {
            return game.getPlayer(playerView);
        }
        return null;
    }

    private void addSystemMessage(String message) {
        addMessage(ChatMessage.createSystemMessage(message));
    }

    private void addMessage(ChatMessage message) {
        try {
            String text = (doc.getLength() == 0 ? "" : "\n") + message.getFormattedMessage();
            doc.insertString(doc.getLength(), text, styleFor(message));
        } catch (BadLocationException ex) {
            txtLog.setText(txtLog.getText() + "\n" + message.getFormattedMessage());
        }
    }

    private void addUltronTableTalk(String message) {
        FThreads.invokeInEdtLater(() -> addMessage(new ChatMessage("Ultron", message)));
    }

    private SimpleAttributeSet styleFor(ChatMessage message) {
        if ("Ultron".equalsIgnoreCase(message.getSource())) {
            return ultronStyle;
        }
        return switch (message.getType()) {
        case SYSTEM, WARNING -> systemStyle;
        default -> playerStyle;
        };
    }
}
