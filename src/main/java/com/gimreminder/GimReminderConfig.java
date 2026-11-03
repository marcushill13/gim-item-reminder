package com.gimreminder;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("gimreminder")
public interface GimReminderConfig extends Config
{
    @ConfigItem(keyName = "serverUrl", name = "Advanced: group service", description = "Shared service address. Managed by plugin maintainer", position = 99, hidden = true)
    default String serverUrl() { return "https://gim-item-reminder.marcushill3313.workers.dev"; }

    @ConfigItem(keyName = "groupCode", name = "Group code", description = "Created or joined automatically in the sidebar. Share only with your GIM members", position = 1, secret = true, hidden = true)
    default String groupCode() { return ""; }

    @ConfigItem(keyName = "creatorToken", name = "Group owner key", description = "Private key saved when you create a group. Do not share this key", position = 7, secret = true, hidden = true)
    default String creatorToken() { return ""; }

    @ConfigItem(keyName = "warnLogout", name = "Logout reminder", description = "Warn before a normal in-game Logout click when tracked items are outstanding", position = 2)
    default boolean warnLogout() { return true; }

    @ConfigItem(keyName = "includeItemIcon", name = "Item image in Discord", description = "Attach the item sprite as a thumbnail. Sends a small PNG to the group backend and Discord.", position = 3)
    default boolean includeItemIcon() { return true; }

    @ConfigItem(keyName = "screenshotMode", name = "Discord screenshots", description = "Optional: uploads a picture of your game canvas to the group's backend and Discord when tracked items move. Other players can see the image.", position = 4, warning = "Screenshots of your game are uploaded to your group backend and Discord. Enable only if comfortable sharing your screen in-game.")
    default ScreenshotMode screenshotMode() { return ScreenshotMode.OFF; }

    @ConfigItem(keyName = "screenshotSize", name = "Screenshot size", description = "Maximum screenshot width; image is JPEG-compressed to limit uploads", position = 5)
    default ScreenshotSize screenshotSize() { return ScreenshotSize.SMALL; }

    @ConfigItem(keyName = "showQuantity", name = "Show quantity", description = "Include the quantity borrowed in Discord messages", position = 6)
    default boolean showQuantity() { return true; }
}
