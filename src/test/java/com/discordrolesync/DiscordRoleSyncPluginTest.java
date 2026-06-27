package com.discordrolesync;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Launches RuneLite with the Discord Role Sync plugin loaded, for local development. Run this class's main().
 */
public class DiscordRoleSyncPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(DiscordRoleSyncPlugin.class);
		RuneLite.main(args);
	}
}
