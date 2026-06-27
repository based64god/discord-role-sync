package com.discordrolesync;

import java.util.Collections;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.clan.ClanMember;
import net.runelite.api.clan.ClanRank;
import net.runelite.api.clan.ClanSettings;
import net.runelite.api.clan.ClanTitle;
import net.runelite.api.events.ClanChannelChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the plugin's orchestration logic — config/event handling, gating, and the link flow —
 * by driving the public event handlers with mocked RuneLite collaborators. The client thread is
 * stubbed to run inline so the lambdas execute synchronously.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class DiscordRoleSyncPluginLogicTest
{
	private static final String GROUP = DiscordRoleSyncConfig.GROUP;
	private static final String TOKEN_KEY = "reportToken";

	@Mock
	private Client client;
	@Mock
	private ClientThread clientThread;
	@Mock
	private DiscordRoleSyncConfig config;
	@Mock
	private ConfigManager configManager;
	@Mock
	private DiscordRoleSyncApiClient api;
	@Mock
	private ChatMessageManager chatMessageManager;

	@InjectMocks
	private DiscordRoleSyncPlugin plugin;

	@Before
	public void runClientThreadInline()
	{
		doAnswer(inv ->
		{
			inv.getArgument(0, Runnable.class).run();
			return null;
		}).when(clientThread).invoke(any(Runnable.class));
	}

	private static ConfigChanged linkNow(String newValue)
	{
		ConfigChanged e = new ConfigChanged();
		e.setGroup(GROUP);
		e.setKey("linkNow");
		e.setNewValue(newValue);
		return e;
	}

	private void clanLoaded()
	{
		plugin.onClanChannelChanged(new ClanChannelChanged(null, 0, false));
	}

	// --- clan reporting gating ------------------------------------------------

	@Test
	public void doesNotReportWhenReportingDisabled()
	{
		when(config.reportClan()).thenReturn(false);
		clanLoaded();
		verify(api, never()).reportClan(any(), any(), any(), any());
	}

	@Test
	public void doesNotReportWhenNotLinked()
	{
		when(config.reportClan()).thenReturn(true);
		when(configManager.getConfiguration(GROUP, TOKEN_KEY)).thenReturn(null);
		clanLoaded();
		verify(api, never()).reportClan(any(), any(), any(), any());
	}

	@Test
	public void doesNotReportWhenNotLoggedIn()
	{
		when(config.reportClan()).thenReturn(true);
		when(configManager.getConfiguration(GROUP, TOKEN_KEY)).thenReturn("tok");
		when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
		clanLoaded();
		verify(api, never()).reportClan(any(), any(), any(), any());
	}

	@Test
	@SuppressWarnings("unchecked")
	public void reportsRosterWhenLinkedAndLoggedIn()
	{
		when(config.reportClan()).thenReturn(true);
		when(config.apiBaseUrl()).thenReturn("https://api.test");
		when(configManager.getConfiguration(GROUP, TOKEN_KEY)).thenReturn("tok");
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);

		ClanSettings settings = mock(ClanSettings.class);
		ClanMember m = mock(ClanMember.class);
		ClanRank rank = new ClanRank(126);
		when(m.getName()).thenReturn("Zezima");
		when(m.getRank()).thenReturn(rank);
		when(settings.getName()).thenReturn("My Clan");
		when(settings.getMembers()).thenReturn(Collections.singletonList(m));
		when(settings.titleForRank(rank)).thenReturn(new ClanTitle(1, "Owner"));
		when(client.getClanSettings()).thenReturn(settings);

		clanLoaded();

		ArgumentCaptor<List<ClanReporter.Member>> roster = ArgumentCaptor.forClass(List.class);
		verify(api).reportClan(eq("https://api.test"), eq("tok"), eq("My Clan"), roster.capture());
		assertEquals(1, roster.getValue().size());
		assertEquals("Zezima", roster.getValue().get(0).name);
	}

	// --- linking flow ---------------------------------------------------------

	@Test
	public void ignoresConfigChangesFromOtherGroups()
	{
		ConfigChanged e = linkNow("true");
		e.setGroup("some-other-plugin");
		plugin.onConfigChanged(e);
		verify(configManager, never()).setConfiguration(any(), any(), any());
		verify(api, never()).redeem(any(), any(), anyLong(), any(), any());
	}

	@Test
	public void ignoresLinkNowToggledOff()
	{
		plugin.onConfigChanged(linkNow("false"));
		verify(configManager, never()).setConfiguration(any(), any(), any());
		verify(api, never()).redeem(any(), any(), anyLong(), any(), any());
	}

	@Test
	public void linkWithBlankKeyNotifiesAndDoesNotRedeem()
	{
		when(config.accessKey()).thenReturn("   ");
		plugin.onConfigChanged(linkNow("true"));

		// The toggle is always reset first...
		verify(configManager).setConfiguration(GROUP, "linkNow", false);
		// ...but with no key we just nudge the user and stop.
		verify(api, never()).redeem(any(), any(), anyLong(), any(), any());
		verify(chatMessageManager).queue(any());
	}

	@Test
	public void linkWhileLoggedOutNotifiesAndDoesNotRedeem()
	{
		when(config.accessKey()).thenReturn("key");
		when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
		plugin.onConfigChanged(linkNow("true"));

		verify(api, never()).redeem(any(), any(), anyLong(), any(), any());
		verify(chatMessageManager).queue(any());
	}

	@Test
	public void linkRedeemsWithTrimmedKeyAndAccountDetails()
	{
		when(config.accessKey()).thenReturn("  key  ");
		when(config.apiBaseUrl()).thenReturn("https://api.test");
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getAccountHash()).thenReturn(987654321L);
		Player local = mock(Player.class);
		when(local.getName()).thenReturn("Zezima");
		when(client.getLocalPlayer()).thenReturn(local);

		plugin.onConfigChanged(linkNow("true"));

		verify(api).redeem(eq("https://api.test"), eq("key"), eq(987654321L), eq("Zezima"), any());
	}

	@Test
	public void successfulRedeemStoresReportToken()
	{
		when(config.accessKey()).thenReturn("key");
		when(config.apiBaseUrl()).thenReturn("https://api.test");
		when(config.reportClan()).thenReturn(false); // keep the post-link report a no-op
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getAccountHash()).thenReturn(1L);
		Player local = mock(Player.class);
		when(local.getName()).thenReturn("Zezima");
		when(client.getLocalPlayer()).thenReturn(local);

		plugin.onConfigChanged(linkNow("true"));

		ArgumentCaptor<DiscordRoleSyncApiClient.RedeemCallback> cb =
			ArgumentCaptor.forClass(DiscordRoleSyncApiClient.RedeemCallback.class);
		verify(api).redeem(any(), any(), anyLong(), any(), cb.capture());

		cb.getValue().onResult(true, "tok-xyz", "Linked Zezima");

		verify(configManager).setConfiguration(GROUP, TOKEN_KEY, "tok-xyz");
	}

	@Test
	public void failedRedeemDoesNotStoreToken()
	{
		when(config.accessKey()).thenReturn("key");
		when(config.apiBaseUrl()).thenReturn("https://api.test");
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getAccountHash()).thenReturn(1L);
		Player local = mock(Player.class);
		when(local.getName()).thenReturn("Zezima");
		when(client.getLocalPlayer()).thenReturn(local);

		plugin.onConfigChanged(linkNow("true"));

		ArgumentCaptor<DiscordRoleSyncApiClient.RedeemCallback> cb =
			ArgumentCaptor.forClass(DiscordRoleSyncApiClient.RedeemCallback.class);
		verify(api).redeem(any(), any(), anyLong(), any(), cb.capture());

		cb.getValue().onResult(false, null, "Link failed");

		verify(configManager, never()).setConfiguration(eq(GROUP), eq(TOKEN_KEY), any());
	}
}
