package com.gimreminder;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.Locale;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.ItemComposition;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.SpritePixels;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.NavigationButton;
import okhttp3.OkHttpClient;

@PluginDescriptor(name = "GIM Item Reminder", description = "Right-click valuable Group Storage gear to track borrowing", tags = {"gim", "group", "storage", "discord"})
public final class GimReminderPlugin extends Plugin
{

    @Inject private Client client;
    @Inject private GimReminderConfig config;
    @Inject private ConfigManager configManager;
    @Inject private ClientToolbar toolbar;
    @Inject private ItemManager itemManager;
    @Inject private OkHttpClient http;
    @Inject private Gson gson;
    @Inject private DrawManager drawManager;
    @Inject private ClientThread clientThread;

    private final StorageSession session = new StorageSession();
    private ExecutorService worker;
    private SharedApi api;
    private GimReminderPanel panel;
    private NavigationButton nav;
    private Set<Integer> tracked = Collections.emptySet();
    private volatile String outstanding = "";
    private volatile String latestPlayerName = "Unknown";
    private volatile String activeGroupCode = "";
    private final AtomicBoolean refreshPending = new AtomicBoolean();
    private final AtomicBoolean groupRequestPending = new AtomicBoolean();
    private long lastRefresh;
    private boolean bypassOneLogout;
    private boolean lastOpen;
    private int closedTicks;
    private volatile String activeOwnerToken = "";

    @Provides GimReminderConfig provideConfig(ConfigManager manager) { return manager.getConfig(GimReminderConfig.class); }

    @Override protected void startUp()
    {
        worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "gim-reminder-worker"); t.setDaemon(true); return t; });
        api = new SharedApi(http, gson);
        activeGroupCode = config.groupCode() == null ? "" : config.groupCode().trim();
        activeOwnerToken = config.creatorToken() == null ? "" : config.creatorToken().trim();
        panel = new GimReminderPanel(this);
        BufferedImage icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D graphics = icon.createGraphics();
        // Small chest icon with a green clasp, rather than a solid square.
        graphics.setColor(new java.awt.Color(107, 75, 40));
        graphics.fillRoundRect(1, 5, 14, 10, 3, 3);
        graphics.setColor(new java.awt.Color(210, 167, 77));
        graphics.fillRoundRect(1, 3, 14, 5, 3, 3);
        graphics.setColor(new java.awt.Color(68, 47, 29));
        graphics.drawRect(1, 7, 13, 7);
        graphics.setColor(new java.awt.Color(99, 206, 131));
        graphics.fillRect(7, 6, 3, 5);
        graphics.dispose();
        nav = NavigationButton.builder().tooltip("GIM Item Reminder").icon(icon).priority(8).panel(panel).build();
        toolbar.addNavigation(nav);
        if (activeGroupCode.isEmpty()) panel.status("Create or join your group to start tracking.");
        refresh();
    }

    @Override protected void shutDown()
    {
        toolbar.removeNavigation(nav);
        session.reset();
        if (worker != null) worker.shutdownNow();
    }

    String groupCode() { return !activeGroupCode.isEmpty() ? activeGroupCode : (config.groupCode() == null ? "" : config.groupCode().trim()); }

    private String playerName()
    {
        return client.getLocalPlayer() == null ? "Unknown" : client.getLocalPlayer().getName();
    }

    private boolean configured()
    {
        return config.serverUrl() != null && !config.serverUrl().trim().isEmpty() && !groupCode().isEmpty();
    }

    private boolean serverConfigured()
    {
        return config.serverUrl() != null && !config.serverUrl().trim().isEmpty();
    }

    private void request(String method, String path, Map<String, Object> body, boolean refreshAfter)
    {
        if (!configured())
        {
            panel.showError("Group not connected", "Create or join a GIM group first. Tracking items across group members requires your shared backend server.");
            return;
        }
        worker.submit(() -> {
            try
            {
                api.call(config.serverUrl(), groupCode(), method, path, body);
                if ("track".equals(path) || "untrack".equals(path))
                {
                    // Update the local allowlist immediately. A withdrawal on
                    // the next tick should not have to wait for a GET refresh.
                    int itemId = ((Number) body.get("itemId")).intValue();
                    Set<Integer> ids = new HashSet<>(tracked);
                    if ("track".equals(path)) ids.add(itemId);
                    else ids.remove(itemId);
                    tracked = Collections.unmodifiableSet(ids);
                    panel.status("track".equals(path) ? "Item added to tracking." : "Item removed from tracking.");
                }
                if (refreshAfter) refresh();
            }
            catch (Exception ex) { panel.status("Could not save: " + ex.getMessage()); }
        });
    }

    void refresh()
    {
        if (!configured() || panel == null || groupRequestPending.get() || !refreshPending.compareAndSet(false, true)) return;
        final String ownName = latestPlayerName;
        worker.submit(() -> {
            try
            {
                JsonObject state = api.call(config.serverUrl(), groupCode(), "GET", "state", Collections.emptyMap());
                Set<Integer> ids = new HashSet<>();
                JsonArray items = state.getAsJsonArray("tracked");
                if (items != null) for (JsonElement item : items) ids.add(item.getAsJsonObject().get("item_id").getAsInt());
                tracked = Collections.unmodifiableSet(ids);
                StringBuilder own = new StringBuilder();
                JsonArray borrows = state.getAsJsonArray("borrowed");
                if (borrows != null) for (JsonElement entry : borrows)
                {
                    JsonObject record = entry.getAsJsonObject();
                    if (ownName.equalsIgnoreCase(record.get("borrower").getAsString()))
                        own.append("• ").append(record.get("item_name").getAsString()).append(" ×")
                            .append(record.get("quantity").getAsInt()).append("\n");
                }
                outstanding = own.toString();
                panel.render(state);
            }
            catch (Exception ex) { panel.status("Unable to load group: " + humanError(ex)); }
            finally { refreshPending.set(false); }
        });
    }

    void createGroup(String name)
    {
        if (!groupRequestPending.compareAndSet(false, true)) return;
        panel.setBusy(true, "Creating group…");
        worker.submit(() -> {
            try
            {
                api.health(config.serverUrl());
                JsonObject result = api.call(config.serverUrl(), "", "POST", "groups",
                    SharedApi.payload("name", name));
                String code = result.get("code").getAsString();
                String creatorToken = result.get("creatorToken").getAsString();
                activeGroupCode = code;
                activeOwnerToken = creatorToken;
                tracked = Collections.emptySet();
                outstanding = "";
                configManager.setConfiguration("gimreminder", "groupCode", code);
                configManager.setConfiguration("gimreminder", "creatorToken", creatorToken);
                panel.updateGroupName(name);
                panel.status("Group created! Share your six-character code.");
                panel.showSuccess(name, code);
            }
            catch (Exception ex) { panel.showError("Unable to create group", humanError(ex)); }
            finally
            {
                groupRequestPending.set(false);
                panel.setBusy(false, null);
                refresh();
            }
        });
    }

    void joinGroup(String code)
    {
        String normalizedCode = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (!normalizedCode.matches("[A-HJ-NP-Z2-9]{6}"))
        {
            panel.showError("Invalid code", "Enter the six-character invite code your teammate shared.");
            return;
        }
        if (!groupRequestPending.compareAndSet(false, true)) return;
        panel.setBusy(true, "Joining group…");
        worker.submit(() -> {
            try
            {
                JsonObject result = api.call(config.serverUrl(), normalizedCode,
                    "GET", "state", Collections.emptyMap());
                boolean sameGroup = normalizedCode.equals(groupCode());
                activeGroupCode = normalizedCode;
                if (!sameGroup)
                {
                    activeOwnerToken = "";
                    configManager.unsetConfiguration("gimreminder", "creatorToken");
                }
                tracked = Collections.emptySet();
                outstanding = "";
                configManager.setConfiguration("gimreminder", "groupCode", normalizedCode);
                panel.updateGroupName(result.get("name").getAsString());
                panel.status("Joined! Right-click equipment in Group Storage to track it.");
            }
            catch (Exception ex) { panel.showError("Unable to join group", humanError(ex)); }
            finally
            {
                groupRequestPending.set(false);
                panel.setBusy(false, null);
                refresh();
            }
        });
    }

    boolean groupOwner() { return activeOwnerToken != null && !activeOwnerToken.isEmpty(); }

    void saveWebhook(String webhook)
    {
        discordRequest("PUT", "discord", SharedApi.payload("webhookUrl", webhook), "Discord connected to this group.");
    }

    void removeWebhook()
    {
        discordRequest("DELETE", "discord", Collections.emptyMap(), "Discord disconnected from this group.");
    }

    void testWebhook()
    {
        discordRequest("POST", "discord/test", Collections.emptyMap(), "Test message sent to Discord.");
    }

    private void discordRequest(String method, String route, Map<String, Object> body, String success)
    {
        if (!groupOwner() || groupCode().isEmpty())
        {
            panel.showError("Group owner only", "Only the person who created this group can change Discord settings.");
            return;
        }
        panel.status("Updating Discord…");
        worker.submit(() -> {
            try
            {
                api.discord(config.serverUrl(), groupCode(), method, route, activeOwnerToken, body);
                panel.status(success);
                refresh();
            }
            catch (Exception ex) { panel.showError("Discord settings", humanError(ex)); }
        });
    }

    private static String humanError(Exception ex)
    {
        String detail = ex.getMessage();
        if (detail == null || detail.isEmpty()) return "The shared group service is unavailable. Please try again later.";
        if (ex instanceof java.net.UnknownHostException || detail.contains("Failed to connect") || detail.contains("timeout"))
            return "The shared group service could not be reached. This is a service setup issue, not something your teammates need to configure.";
        return detail;
    }

    void untrack(int id) { request("POST", "untrack", SharedApi.payload("itemId", id), true); }

    private void track(int id)
    {
        ItemComposition item = itemManager.getItemComposition(id);
        request("POST", "track", SharedApi.payload("itemId", id, "name", item.getName()), true);
    }

    private boolean storageVisible()
    {
        Widget frame = client.getWidget(InterfaceID.SharedBank.FRAME);
        Widget items = client.getWidget(InterfaceID.SharedBank.ITEMS);
        return frame != null && !frame.isHidden() && items != null && !items.isHidden();
    }

    private boolean isStorageMenu(MenuEntry entry)
    {
        if (!storageVisible()) return false;
        if (entry.getParam1() == InterfaceID.SharedBank.ITEMS) return true;
        Widget widget = entry.getWidget();
        for (int depth = 0; widget != null && depth < 6; depth++, widget = widget.getParent())
        {
            if (widget.getId() == InterfaceID.SharedBank.ITEMS) return true;
        }
        return false;
    }

    private int itemIdFromMenu(MenuEntry entry)
    {
        if (entry.getItemId() > 0) return entry.getItemId();
        ItemContainer container = client.getItemContainer(InventoryID.INV_GROUP_TEMP);
        int slot = entry.getParam0();
        if (container == null || slot < 0 || slot >= container.size()) return -1;
        Item item = container.getItem(slot);
        return item == null ? -1 : item.getId();
    }

    private boolean isEquipment(int id)
    {
        ItemComposition composition = itemManager.getItemComposition(id);
        if (composition.getInventoryActions() != null)
        {
            for (String action : composition.getInventoryActions())
            {
                if ("Wear".equalsIgnoreCase(action) || "Wield".equalsIgnoreCase(action)
                    || "Equip".equalsIgnoreCase(action)) return true;
            }
        }
        net.runelite.client.game.ItemStats stats = itemManager.getItemStats(id);
        return stats != null && stats.isEquipable();
    }

    /** An item-specific context menu entry, never a generic sidebar selection. */
    @Subscribe public void onMenuEntryAdded(MenuEntryAdded event)
    {
        MenuEntry source = event.getMenuEntry();
        if (!"Examine".equalsIgnoreCase(source.getOption()) || !isStorageMenu(source)) return;
        int itemId = itemIdFromMenu(source);
        if (itemId <= 0 || !isEquipment(itemId)) return;
        boolean alreadyTracked = tracked.contains(itemId);
        String label = alreadyTracked ? "Stop tracking for GIM" : "Track for GIM";
        for (MenuEntry entry : client.getMenu().getMenuEntries())
        {
            if (label.equals(entry.getOption()) && itemId == entry.getItemId()) return;
        }
        String target = source.getTarget();
        client.getMenu().createMenuEntry(-1)
            .setOption(label)
            .setTarget(target)
            .setType(MenuAction.RUNELITE)
            .setItemId(itemId)
            .onClick(e -> {
                if (alreadyTracked) untrack(itemId);
                else track(itemId);
            });
    }

    @Subscribe public void onGameStateChanged(GameStateChanged e)
    {
        if (e.getGameState() != GameState.LOGGED_IN)
        {
            session.reset();
            lastOpen = false;
            closedTicks = 0;
        }
        else refresh();
    }

    /**
     * Use the same authoritative signal as Dink: shared storage opens with a
     * temporary *player inventory*. Only the final saved inventory delta is
     * a committed transaction. A cancelled storage visit sends nothing.
     */
    @Subscribe public void onWidgetLoaded(WidgetLoaded event)
    {
        if (event.getGroupId() != InterfaceID.SHARED_BANK) return;
        session.reset();
        closedTicks = 0;
        // On widget load, the temporary inventory may arrive on a later tick.
        clientThread.invokeLater(() -> {
            if (!storageVisible()) return true;
            Map<Integer, Integer> inv = playerInventory();
            if (inv == null) return false;
            if (!session.isOpen()) session.observe(inv);
            return true;
        });
    }

    /** Saving modal closes when Group Storage changes are actually committed. */
    @Subscribe public void onWidgetClosed(WidgetClosed event)
    {
        if (event.getGroupId() != InterfaceID.LOADING_ICON_MODAL) return;
        Widget textWidget = client.getWidget(InterfaceID.LoadingIconModal.TEXT);
        if (textWidget == null || textWidget.getText() == null
            || !textWidget.getText().toLowerCase(Locale.ROOT).contains("saving")) return;
        Map<Integer, Integer> finalInventory = playerInventory();
        if (finalInventory != null) session.observe(finalInventory);
        session.saveRequested();
        deliverSavedMovements();
    }

    private void deliverSavedMovements()
    {
        if (!session.isOpen()) return;
        deliverMovements(session.closeAsStorageMovements());
    }

    private void deliverMovements(Map<Integer, Integer> storageMovements)
    {
        closedTicks = 0;
        if (!configured() || storageMovements.isEmpty()) return;
        int count = 0;
        for (Map.Entry<Integer, Integer> movement : storageMovements.entrySet())
        {
            if (!tracked.contains(movement.getKey())) continue;
            sendMovement(movement.getKey(), movement.getValue());
            count++;
        }
        if (count > 0) panel.status("Saved " + count + " tracked item change(s). Updating group…");
    }

    @Subscribe public void onGameTick(GameTick tick)
    {
        if (client.getGameState() != GameState.LOGGED_IN) return;
        latestPlayerName = playerName();
        boolean open = storageVisible();
        if (open)
        {
            if (!lastOpen && session.isOpen()) session.reset();
            Map<Integer, Integer> inv = playerInventory();
            if (inv != null) session.observe(inv);
            closedTicks = 0;
        }
        else if (session.isOpen())
        {
            // Back to bank typically triggers the Saving modal (handled above).
            // Clicking X can close the shared bank without that modal event.
            // Never trust INV_PLAYER_TEMP here: it may contain an unsaved move.
            // Only emit when the *server-backed* inventory has changed.
            if (session.isSaved())
            {
                deliverSavedMovements();
            }
            else
            {
                ++closedTicks;
                Map<Integer, Integer> committedInventory = realPlayerInventory();
                if (session.differsFromOpeningInventory(committedInventory))
                {
                    deliverMovements(session.closeFromConfirmedInventory(committedInventory));
                }
                else if (closedTicks > 8)
                {
                    // X can also discard the transaction. No inventory
                    // change means no item was borrowed or returned.
                    session.reset();
                    closedTicks = 0;
                }
            }
        }
        if (open != lastOpen)
        {
            lastOpen = open;
            panel.storageStatus(open);
        }
        if (configured() && System.currentTimeMillis() - lastRefresh > 15000)
        {
            lastRefresh = System.currentTimeMillis();
            refresh();
        }
    }

    /** Snapshot player inventory rather than shared-bank item widgets. */
    private Map<Integer, Integer> playerInventory()
    {
        ItemContainer container = client.getItemContainer(InventoryID.INV_PLAYER_TEMP);
        if (container == null) container = client.getItemContainer(InventoryID.INV);
        return amounts(container);
    }

    /** The real inventory is authoritative after the shared-bank window closes. */
    private Map<Integer, Integer> realPlayerInventory()
    {
        return amounts(client.getItemContainer(InventoryID.INV));
    }

    private Map<Integer, Integer> amounts(ItemContainer container)
    {
        if (container == null) return null;
        Map<Integer, Integer> items = new java.util.HashMap<>();
        for (Item item : container.getItems())
        {
            if (item != null && item.getId() > 0 && item.getQuantity() > 0)
                items.merge(item.getId(), item.getQuantity(), Integer::sum);
        }
        return items;
    }

    @Subscribe public void onMenuOptionClicked(MenuOptionClicked event)
    {
        MenuEntry entry = event.getMenuEntry();
        if (storageVisible() && (entry.getParam1() == InterfaceID.SharedBank.SAVE_BUTTON
            || (entry.getWidget() != null && entry.getWidget().getId() == InterfaceID.SharedBank.SAVE_BUTTON)))
        {
            // Save-button detection is a fallback; Dink's Saving modal is
            // the main commit signal. Keep the last temporary inventory state.
            Map<Integer, Integer> currentInventory = playerInventory();
            if (currentInventory != null) session.observe(currentInventory);
            session.saveRequested();
        }
        if (!config.warnLogout() || outstanding.isEmpty()) return;
        String option = event.getMenuOption();
        if (option == null || !(option.equalsIgnoreCase("Logout") || option.equalsIgnoreCase("Log out"))) return;
        if (bypassOneLogout) { bypassOneLogout = false; return; }
        event.consume();
        String items = outstanding;
        SwingUtilities.invokeLater(() -> {
            int answer = JOptionPane.showConfirmDialog(null,
                "You currently have these GIM items borrowed:\n\n" + items + "\nLog out without returning them?",
                "GIM Item Reminder", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (answer == JOptionPane.YES_OPTION)
            {
                bypassOneLogout = true;
                panel.status("Click Logout again to confirm.");
            }
        });
    }

    private void sendMovement(int id, int delta)
    {
        String name = itemManager.getItemComposition(id).getName();
        String player = playerName();
        String icon = null;
        if (config.includeItemIcon())
        {
            SpritePixels sprite = client.createItemSprite(id, 1, 1, SpritePixels.DEFAULT_SHADOW_COLOR,
                0, false, Constants.CLIENT_DEFAULT_ZOOM);
            if (sprite != null) icon = DiscordVisuals.pngBase64(sprite.toBufferedImage());
        }
        final String iconImage = icon;
        boolean capture = config.screenshotMode() == ScreenshotMode.BORROW_AND_RETURN
            || (delta < 0 && config.screenshotMode() == ScreenshotMode.BORROW_ONLY);
        if (!capture)
        {
            queueMovement(id, name, player, delta, iconImage, null);
            return;
        }
        CompletableFuture<String> screenshot = new CompletableFuture<>();
        drawManager.requestNextFrameListener(frame -> {
            try { screenshot.complete(DiscordVisuals.jpegBase64(frame, config.screenshotSize().maxWidth())); }
            catch (RuntimeException e) { screenshot.complete(null); }
        });
        CompletableFuture.delayedExecutor(2, TimeUnit.SECONDS).execute(() -> screenshot.complete(null));
        screenshot.thenAccept(encoded -> queueMovement(id, name, player, delta, iconImage, encoded));
    }

    private void queueMovement(int id, String name, String player, int delta, String icon, String screenshot)
    {
        Map<String, Object> body = SharedApi.payload("itemId", id, "itemName", name,
            "delta", delta, "player", player, "showQuantity", config.showQuantity());
        if (icon != null) body.put("iconBase64", icon);
        if (screenshot != null) body.put("screenshotBase64", screenshot);
        request("POST", "movement", body, true);
    }
}
