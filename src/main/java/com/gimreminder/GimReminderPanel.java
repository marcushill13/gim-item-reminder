package com.gimreminder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.PluginPanel;

/**
 * Two actual screens rather than leaving Create/Join controls visible inside a
 * joined group. Discord is configured inside the group page, not in RuneLite's
 * global config. Only the group creator can edit the shared webhook.
 */
public final class GimReminderPanel extends PluginPanel
{
    private static final Color MUTED = new Color(177, 184, 194);
    private static final Color WHITE = new Color(236, 239, 245);
    private static final Color RED = new Color(236, 101, 97);
    private static final Color GREEN = new Color(110, 213, 149);
    private static final Color CARD = new Color(43, 47, 53);
    private static final Color BG = new Color(32, 35, 39);

    private static final String HOME = "home";
    private static final String GROUP = "group";

    private final GimReminderPlugin plugin;
    private final CardLayout views = new CardLayout();
    private final JPanel pages = new JPanel(views);
    private final JLabel activity = new JLabel("Ready");
    private final JLabel groupName = label("Group", WHITE, 17, true);
    private final JLabel groupCode = label("------", MUTED, 15, true);
    private final JLabel storage = label("Storage: closed", MUTED, 11, false);
    private final JLabel discordStatus = label("Not connected", MUTED, 12, false);
    private final JLabel discordOwnerNote = label("", MUTED, 11, false);
    private final JPasswordField webhookInput = new JPasswordField(16);
    private final JButton saveWebhook = button("Save");
    private final JButton testWebhook = button("Test");
    private final JButton removeWebhook = button("Remove");
    private final JPanel borrowedList = verticalPanel();
    private final JPanel trackedList = verticalPanel();
    private JPanel borrowedCard;
    private JPanel trackedCard;
    private final JButton createButton = button("Create a group");
    private final JButton joinButton = button("Join with code");
    private final JButton backButton = button("Back to my group");
    private boolean choosingGroup;

    GimReminderPanel(GimReminderPlugin plugin)
    {
        super(false);
        this.plugin = plugin;
        setLayout(new BorderLayout(0, 8));
        setPreferredSize(new Dimension(248, 450));
        setBorder(BorderFactory.createEmptyBorder(10, 9, 10, 9));
        setBackground(BG);

        JPanel titleArea = verticalPanel();
        JLabel title = label("GIM ITEM REMINDER", WHITE, 15, true);
        titleArea.add(title);
        titleArea.add(Box.createVerticalStrut(3));
        titleArea.add(label("Group Storage equipment tracker", MUTED, 11, false));
        add(titleArea, BorderLayout.NORTH);

        pages.setOpaque(false);
        pages.add(makeHomePage(), HOME);
        pages.add(makeGroupPage(), GROUP);
        add(pages, BorderLayout.CENTER);

        activity.setForeground(MUTED);
        activity.setFont(activity.getFont().deriveFont(11f));
        activity.setBorder(BorderFactory.createEmptyBorder(7, 4, 0, 2));
        add(activity, BorderLayout.SOUTH);

        if (plugin.groupCode().isEmpty()) showHome();
        else showGroup();
    }

    private JPanel makeHomePage()
    {
        JPanel panel = verticalPanel();
        panel.add(Box.createVerticalStrut(25));
        panel.add(label("Welcome", WHITE, 21, true));
        panel.add(Box.createVerticalStrut(10));
        panel.add(description("Create a group once, then give your teammates the six-character invite code."));
        panel.add(Box.createVerticalStrut(20));
        createButton.addActionListener(e -> createGroup());
        joinButton.addActionListener(e -> joinGroup());
        createButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        joinButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        fixHeight(createButton, 36);
        fixHeight(joinButton, 36);
        panel.add(createButton);
        panel.add(Box.createVerticalStrut(8));
        panel.add(joinButton);
        backButton.addActionListener(e -> showGroup());
        panel.add(Box.createVerticalStrut(14));
        panel.add(backButton);
        return panel;
    }

    private JPanel makeGroupPage()
    {
        JPanel page = verticalPanel();

        JPanel summary = card();
        JPanel nameRow = new JPanel(new BorderLayout(6, 0));
        nameRow.setOpaque(false);
        nameRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        nameRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        nameRow.add(groupName, BorderLayout.CENTER);
        JButton switchGroup = button("Switch");
        switchGroup.setToolTipText("Create or join a different group");
        switchGroup.setMargin(new Insets(3, 7, 3, 7));
        switchGroup.addActionListener(e -> showHome());
        nameRow.add(switchGroup, BorderLayout.EAST);
        summary.add(nameRow);
        summary.add(Box.createVerticalStrut(6));
        JPanel codeRow = new JPanel(new BorderLayout(4, 0));
        codeRow.setOpaque(false);
        codeRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        codeRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 27));
        codeRow.add(groupCode, BorderLayout.WEST);
        JButton copy = button("Copy");
        copy.addActionListener(e -> {
            String code = plugin.groupCode();
            if (!code.isEmpty())
            {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(code), null);
                status("Invite code copied.");
            }
        });
        codeRow.add(copy, BorderLayout.EAST);
        summary.add(codeRow);
        summary.add(Box.createVerticalStrut(8));
        JPanel storageRow = new JPanel(new BorderLayout(4, 0));
        storageRow.setOpaque(false);
        storageRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        storageRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 27));
        storageRow.add(storage, BorderLayout.CENTER);
        JButton refresh = button("Sync");
        refresh.setMargin(new Insets(3, 8, 3, 8));
        refresh.addActionListener(e -> plugin.refresh());
        storageRow.add(refresh, BorderLayout.EAST);
        summary.add(storageRow);
        capHeight(summary);
        page.add(summary);
        page.add(Box.createVerticalStrut(10));

        JPanel discordCard = card();
        discordCard.add(label("DISCORD NOTIFICATIONS", WHITE, 12, true));
        discordCard.add(Box.createVerticalStrut(6));
        discordStatus.setPreferredSize(new Dimension(195, 19));
        discordStatus.setMaximumSize(new Dimension(Integer.MAX_VALUE, 19));
        discordCard.add(discordStatus);
        discordCard.add(Box.createVerticalStrut(8));
        discordOwnerNote.setAlignmentX(Component.LEFT_ALIGNMENT);
        discordOwnerNote.setPreferredSize(new Dimension(195, 18));
        discordOwnerNote.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));
        discordCard.add(discordOwnerNote);
        discordOwnerNote.setText(plugin.groupOwner() ? "Paste webhook URL below" : "Only the group creator can edit");
        discordCard.add(Box.createVerticalStrut(6));
        webhookInput.setToolTipText("Paste your Discord channel webhook URL here. It is stored on the group server.");
        webhookInput.setMaximumSize(new Dimension(Integer.MAX_VALUE, 27));
        webhookInput.setAlignmentX(Component.LEFT_ALIGNMENT);
        discordCard.add(webhookInput);
        discordCard.add(Box.createVerticalStrut(7));
        JPanel actions = new JPanel(new GridLayout(1, 3, 4, 0));
        actions.setOpaque(false);
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);
        actions.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        saveWebhook.setMargin(new Insets(3, 3, 3, 3));
        testWebhook.setMargin(new Insets(3, 3, 3, 3));
        removeWebhook.setMargin(new Insets(3, 3, 3, 3));
        saveWebhook.addActionListener(e -> {
            String url = new String(webhookInput.getPassword()).trim();
            if (!url.startsWith("https://discord.com/api/webhooks/")
                && !url.startsWith("https://discordapp.com/api/webhooks/"))
            {
                showError("Invalid webhook", "Paste the full Discord channel webhook URL.");
                return;
            }
            webhookInput.setText(""); // Never leave the webhook visible after saving.
            plugin.saveWebhook(url);
        });
        testWebhook.addActionListener(e -> plugin.testWebhook());
        removeWebhook.addActionListener(e -> {
            if (JOptionPane.showConfirmDialog(this,
                "Remove the Discord webhook from this group?", "Remove Discord",
                JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) plugin.removeWebhook();
        });
        actions.add(saveWebhook);
        actions.add(testWebhook);
        actions.add(removeWebhook);
        discordCard.add(actions);
        capHeight(discordCard);
        page.add(discordCard);
        page.add(Box.createVerticalStrut(13));

        borrowedCard = card();
        borrowedCard.add(label("CURRENTLY BORROWED", RED, 12, true));
        borrowedCard.add(Box.createVerticalStrut(6));
        borrowedCard.add(borrowedList);
        capHeight(borrowedCard);
        page.add(borrowedCard);
        page.add(Box.createVerticalStrut(10));

        trackedCard = card();
        trackedCard.add(label("TRACKED ITEMS", WHITE, 12, true));
        trackedCard.add(Box.createVerticalStrut(6));
        trackedCard.add(trackedList);
        capHeight(trackedCard);
        page.add(trackedCard);
        page.add(Box.createVerticalStrut(8));
        page.add(description("To track: right-click equipment in Group Storage → Track for GIM."));

        JScrollPane scroll = new JScrollPane(page);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(14);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        JPanel wrapped = new JPanel(new BorderLayout());
        wrapped.setOpaque(false);
        wrapped.add(scroll, BorderLayout.CENTER);
        refreshOwnerState();
        return wrapped;
    }

    private void createGroup()
    {
        JTextField field = new JTextField(16);
        int choice = JOptionPane.showConfirmDialog(this, new Object[]{"Group name", field},
            "Create GIM group", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) return;
        String name = field.getText().trim();
        if (name.isEmpty()) { showError("Group name required", "Enter a group name."); return; }
        plugin.createGroup(name);
    }

    private void joinGroup()
    {
        JTextField field = new JTextField(9);
        int choice = JOptionPane.showConfirmDialog(this, new Object[]{"Six-character group code", field},
            "Join GIM group", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice == JOptionPane.OK_OPTION) plugin.joinGroup(field.getText().trim());
    }

    private void showHome()
    {
        choosingGroup = true;
        backButton.setVisible(!plugin.groupCode().isEmpty());
        views.show(pages, HOME);
        revalidate();
        repaint();
    }

    private void showGroup()
    {
        choosingGroup = false;
        groupCode.setText("CODE: " + plugin.groupCode());
        refreshOwnerState();
        views.show(pages, GROUP);
        revalidate();
        repaint();
    }

    private void refreshOwnerState()
    {
        boolean owner = plugin.groupOwner();
        webhookInput.setEnabled(owner);
        saveWebhook.setEnabled(owner);
        testWebhook.setEnabled(owner);
        removeWebhook.setEnabled(owner);
        discordOwnerNote.setText(owner ? "Paste webhook URL below" : "Only the group creator can edit");
    }

    void setBusy(boolean busy, String message)
    {
        runUi(() -> {
            createButton.setEnabled(!busy);
            joinButton.setEnabled(!busy);
            if (message != null) status(message);
        });
    }

    void showError(String title, String message)
    {
        status(message);
        runUi(() -> JOptionPane.showMessageDialog(this, message, title, JOptionPane.ERROR_MESSAGE));
    }

    void showSuccess(String name, String code)
    {
        runUi(() -> {
            int choice = JOptionPane.showOptionDialog(this,
                "Group created: " + name + "\nInvite code: " + code,
                "Your GIM group", JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE,
                null, new Object[]{"Copy invite code", "Done"}, "Copy invite code");
            if (choice == 0)
            {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(code), null);
                status("Invite code copied.");
            }
        });
    }

    void updateGroupName(String name)
    {
        runUi(() -> {
            groupName.setText(name == null || name.isEmpty() ? "Loading group…" : escape(name));
            showGroup();
        });
    }

    void storageStatus(boolean open)
    {
        runUi(() -> {
            storage.setText(open ? "● Group Storage open" : "Group Storage closed");
            storage.setForeground(open ? GREEN : MUTED);
        });
    }

    void status(String text)
    {
        runUi(() -> {
            activity.setText("<html><div style='width:218px'>" + escape(text) + "</div></html>");
            activity.setToolTipText(text);
        });
    }

    void render(JsonObject state)
    {
        runUi(() -> {
            String name = state.has("name") ? state.get("name").getAsString() : "Group";
            groupName.setText(escape(name));
            groupCode.setText("CODE: " + plugin.groupCode());
            refreshOwnerState();
            if (!choosingGroup) views.show(pages, GROUP);
            boolean connected = state.has("discord_connected") && state.get("discord_connected").getAsBoolean();
            discordStatus.setText(connected ? "● Connected" : "● Not connected");
            discordStatus.setForeground(connected ? GREEN : MUTED);

            borrowedList.removeAll();
            JsonArray borrowed = state.getAsJsonArray("borrowed");
            if (borrowed == null || borrowed.size() == 0)
                borrowedList.add(description("Nothing borrowed at the moment."));
            else for (JsonElement item : borrowed)
            {
                JsonObject b = item.getAsJsonObject();
                String nameText = b.get("item_name").getAsString();
                String borrower = b.get("borrower").getAsString();
                int qty = b.get("quantity").getAsInt();
                borrowedList.add(itemRow(nameText + (qty > 1 ? " ×" + qty : ""), borrower, RED, null));
            }

            trackedList.removeAll();
            JsonArray tracked = state.getAsJsonArray("tracked");
            if (tracked == null || tracked.size() == 0)
                trackedList.add(description("No items tracked yet."));
            else for (JsonElement item : tracked)
            {
                JsonObject entry = item.getAsJsonObject();
                int id = entry.get("item_id").getAsInt();
                trackedList.add(itemRow(entry.get("item_name").getAsString(), "", WHITE, () -> plugin.untrack(id)));
            }
            borrowedList.invalidate();
            trackedList.invalidate();
            borrowedCard.invalidate();
            trackedCard.invalidate();
            borrowedList.revalidate();
            trackedList.revalidate();
            capHeight(borrowedCard);
            capHeight(trackedCard);
            revalidate();
            repaint();
        });
    }

    private JPanel itemRow(String name, String subtitle, Color color, Runnable remove)
    {
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(5, 0, 6, 0));
        JPanel text = verticalPanel();
        text.add(label(escape(name), color, 12, true));
        if (!subtitle.isEmpty()) text.add(label("Borrowed by " + escape(subtitle), MUTED, 11, false));
        row.add(text, BorderLayout.CENTER);
        if (remove != null)
        {
            JButton x = button("×");
            x.setToolTipText("Stop tracking this item");
            x.setMargin(new Insets(0, 5, 0, 5));
            x.addActionListener(e -> remove.run());
            row.add(x, BorderLayout.EAST);
        }
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 55));
        return row;
    }

    private static JPanel verticalPanel()
    {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    private static void capHeight(JPanel panel)
    {
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height));
    }

    private static JPanel card()
    {
        JPanel panel = verticalPanel();
        panel.setBackground(CARD);
        panel.setOpaque(true);
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        return panel;
    }

    private static JLabel description(String text)
    {
        StringBuilder wrapped = new StringBuilder("<html>");
        int length = 0;
        int lines = 1;
        for (String word : text.split("\\s+"))
        {
            if (length > 0 && length + word.length() > 27)
            {
                wrapped.append("<br>");
                length = 0;
                lines++;
            }
            if (length > 0) { wrapped.append(' '); length++; }
            wrapped.append(escape(word));
            length += word.length();
        }
        wrapped.append("</html>");
        JLabel label = new JLabel(wrapped.toString());
        label.setForeground(MUTED);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setPreferredSize(new Dimension(195, Math.max(17, lines * 16)));
        label.setMaximumSize(new Dimension(Integer.MAX_VALUE, Math.max(17, lines * 16)));
        return label;
    }

    private static JLabel label(String text, Color color, int size, boolean bold)
    {
        JLabel label = new JLabel(text);
        label.setForeground(color);
        label.setFont(label.getFont().deriveFont(bold ? Font.BOLD : Font.PLAIN, (float) size));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private static JButton button(String name)
    {
        JButton button = new JButton(name);
        button.setFocusPainted(false);
        return button;
    }

    private static void fixHeight(JButton button, int height)
    {
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
        button.setPreferredSize(new Dimension(210, height));
    }

    private static String escape(String input)
    {
        return String.valueOf(input).replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static void runUi(Runnable work)
    {
        if (SwingUtilities.isEventDispatchThread()) work.run();
        else SwingUtilities.invokeLater(work);
    }
}
