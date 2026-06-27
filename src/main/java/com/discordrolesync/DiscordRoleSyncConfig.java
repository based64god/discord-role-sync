package com.discordrolesync;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(DiscordRoleSyncConfig.GROUP)
public interface DiscordRoleSyncConfig extends Config
{
	String GROUP = "discordrolesync";

	@ConfigItem(
		keyName = "apiBaseUrl",
		name = "Backend URL",
		description = "Base URL of the backend (no trailing slash).",
		position = 0
	)
	default String apiBaseUrl()
	{
		return "https://viggora.app";
	}

	@ConfigSection(
		name = "Link account",
		description = "One-time linking of this OSRS account to your Discord.",
		position = 1
	)
	String linkSection = "linkSection";

	@ConfigItem(
		keyName = "accessKey",
		name = "Access key",
		description = "Paste the one-time key the Discord bot gave you from /link.",
		position = 2,
		section = linkSection
	)
	default String accessKey()
	{
		return "";
	}

	@ConfigItem(
		keyName = "linkNow",
		name = "Link this account",
		description = "Toggle on while logged in to link the current account. It resets itself.",
		position = 3,
		section = linkSection
	)
	default boolean linkNow()
	{
		return false;
	}

	@ConfigSection(
		name = "Clan sync",
		description = "Report your clan roster so ranks sync to Discord roles.",
		position = 4
	)
	String clanSection = "clanSection";

	@ConfigItem(
		keyName = "reportClan",
		name = "Report clan roster",
		description = "Periodically send your clan's roster (names + ranks) to the backend. Trust is by "
			+ "consensus across members, so this is safe to leave on.",
		position = 5,
		section = clanSection
	)
	default boolean reportClan()
	{
		return true;
	}
}
