package com.gimreminder;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/** Development-only entry point; launches a standalone RuneLite instance. */
public final class GimReminderPluginTest
{
    public static void main(String[] args) throws Exception
    {
        ExternalPluginManager.loadBuiltin(GimReminderPlugin.class);
        RuneLite.main(args);
    }
}
