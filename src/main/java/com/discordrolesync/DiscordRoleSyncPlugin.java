package com.discordrolesync;

import com.google.inject.Provides;
import java.time.temporal.ChronoUnit;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.events.ClanChannelChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.task.Schedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PluginDescriptor(
	name = "Discord Role Sync",
	description = "Link your Old School RuneScape account and clan ranks to Discord roles.",
	tags = {"discord", "clan", "rank", "link", "role", "sync"}
)
public class DiscordRoleSyncPlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(DiscordRoleSyncPlugin.class);
	private static final String REPORT_TOKEN_KEY = "reportToken";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private DiscordRoleSyncConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private DiscordRoleSyncApiClient api;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Provides
	DiscordRoleSyncConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(DiscordRoleSyncConfig.class);
	}

	@Override
	protected void startUp()
	{
		log.debug("Discord Role Sync started");
	}

	@Override
	protected void shutDown()
	{
		log.debug("Discord Role Sync stopped");
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!DiscordRoleSyncConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}

		// "Link this account" acts like a button: when toggled on, reset it and run the link.
		if ("linkNow".equals(event.getKey()) && "true".equals(event.getNewValue()))
		{
			configManager.setConfiguration(DiscordRoleSyncConfig.GROUP, "linkNow", false);
			linkAccount();
		}
	}

	@Subscribe
	public void onClanChannelChanged(ClanChannelChanged event)
	{
		// Clan data just (re)loaded — push a fresh roster.
		reportClan();
	}

	/** Periodic roster push so rank changes propagate even without a clan reload. */
	@Schedule(period = 10, unit = ChronoUnit.MINUTES, asynchronous = true)
	public void scheduledReport()
	{
		reportClan();
	}

	private void linkAccount()
	{
		final String key = config.accessKey().trim();
		if (key.isEmpty())
		{
			notifyUser("Paste your access key into the plugin settings first.");
			return;
		}

		clientThread.invoke(() ->
		{
			if (client.getGameState() != GameState.LOGGED_IN)
			{
				notifyUser("Log into your OSRS account before linking.");
				return;
			}

			final long accountHash = client.getAccountHash();
			final Player local = client.getLocalPlayer();
			final String name = local != null ? local.getName() : null;
			if (accountHash == -1L || name == null)
			{
				notifyUser("Could not read your account yet — try again in a moment.");
				return;
			}

			api.redeem(config.apiBaseUrl(), key, accountHash, name, (ok, token, message) ->
			{
				if (ok && token != null)
				{
					configManager.setConfiguration(DiscordRoleSyncConfig.GROUP, REPORT_TOKEN_KEY, token);
					notifyUser("✓ Linked " + name + " to your Discord. Clan reporting is on.");
					reportClan();
				}
				else
				{
					notifyUser(message != null ? message : "Linking failed.");
				}
			});
		});
	}

	private void reportClan()
	{
		if (!config.reportClan())
		{
			return;
		}

		final String token = configManager.getConfiguration(DiscordRoleSyncConfig.GROUP, REPORT_TOKEN_KEY);
		if (token == null || token.isEmpty())
		{
			return; // not linked yet — nothing to authenticate with
		}

		clientThread.invoke(() ->
		{
			if (client.getGameState() != GameState.LOGGED_IN)
			{
				return;
			}
			final ClanReporter.Snapshot snapshot = ClanReporter.snapshot(client);
			if (snapshot == null)
			{
				return; // not in a clan, or clan not loaded yet
			}
			api.reportClan(config.apiBaseUrl(), token, snapshot.clanName, snapshot.members);
		});
	}

	private void notifyUser(String message)
	{
		clientThread.invoke(() -> chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage("[Discord Role Sync] " + message)
			.build()));
	}
}
