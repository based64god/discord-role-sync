package com.discordrolesync;

import java.util.Arrays;
import java.util.Collections;
import net.runelite.api.Client;
import net.runelite.api.clan.ClanMember;
import net.runelite.api.clan.ClanRank;
import net.runelite.api.clan.ClanSettings;
import net.runelite.api.clan.ClanTitle;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Unit tests for {@link ClanReporter#snapshot}, the in-client clan roster reader. */
@RunWith(MockitoJUnitRunner.Silent.class)
public class ClanReporterTest
{
	@Mock
	private Client client;

	@Mock
	private ClanSettings settings;

	private static ClanMember member(String name, ClanRank rank)
	{
		ClanMember m = mock(ClanMember.class);
		when(m.getName()).thenReturn(name);
		when(m.getRank()).thenReturn(rank);
		return m;
	}

	@Test
	public void returnsNullWhenNoClanSettings()
	{
		when(client.getClanSettings()).thenReturn(null);
		assertNull(ClanReporter.snapshot(client));
	}

	@Test
	public void returnsNullWhenRosterIsNull()
	{
		when(client.getClanSettings()).thenReturn(settings);
		when(settings.getMembers()).thenReturn(null);
		assertNull(ClanReporter.snapshot(client));
	}

	@Test
	public void returnsNullWhenRosterIsEmpty()
	{
		when(client.getClanSettings()).thenReturn(settings);
		when(settings.getMembers()).thenReturn(Collections.emptyList());
		assertNull(ClanReporter.snapshot(client));
	}

	@Test
	public void buildsMembersWithRankAndTitle()
	{
		ClanRank ownerRank = new ClanRank(126);
		ClanRank memberRank = new ClanRank(5);
		// Build the mocked members up front: stubbing them inside the getMembers() stub would
		// nest stubbing and trip Mockito's UnfinishedStubbingException.
		ClanMember owner = member("Zezima", ownerRank);
		ClanMember plain = member("Bob", memberRank);

		when(client.getClanSettings()).thenReturn(settings);
		when(settings.getName()).thenReturn("My Clan");
		when(settings.getMembers()).thenReturn(Arrays.asList(owner, plain));
		when(settings.titleForRank(ownerRank)).thenReturn(new ClanTitle(1, "Owner"));
		when(settings.titleForRank(memberRank)).thenReturn(null);

		ClanReporter.Snapshot snap = ClanReporter.snapshot(client);

		assertNotNull(snap);
		assertEquals("My Clan", snap.clanName);
		assertEquals(2, snap.members.size());

		ClanReporter.Member first = snap.members.get(0);
		assertEquals("Zezima", first.name);
		assertEquals(126, first.rank);
		assertEquals("Owner", first.title);

		ClanReporter.Member second = snap.members.get(1);
		assertEquals("Bob", second.name);
		assertEquals(5, second.rank);
		assertNull("title should be null when the rank has no title", second.title);
	}

	@Test
	public void usesMinusOneWhenRankIsNull()
	{
		ClanMember noRank = member("NoRank", null);
		when(client.getClanSettings()).thenReturn(settings);
		when(settings.getName()).thenReturn("C");
		when(settings.getMembers()).thenReturn(Collections.singletonList(noRank));

		ClanReporter.Snapshot snap = ClanReporter.snapshot(client);

		assertNotNull(snap);
		assertEquals(1, snap.members.size());
		assertEquals(-1, snap.members.get(0).rank);
		assertNull(snap.members.get(0).title);
	}

	@Test
	public void skipsNullEntriesAndMembersWithoutNames()
	{
		ClanMember real = member("Real", new ClanRank(1));
		ClanMember unnamed = member(null, new ClanRank(2));
		when(client.getClanSettings()).thenReturn(settings);
		when(settings.getName()).thenReturn("C");
		when(settings.titleForRank(any())).thenReturn(null);
		when(settings.getMembers()).thenReturn(Arrays.asList(real, null, unnamed));

		ClanReporter.Snapshot snap = ClanReporter.snapshot(client);

		assertNotNull(snap);
		assertEquals(1, snap.members.size());
		assertEquals("Real", snap.members.get(0).name);
	}

	@Test
	public void returnsNullWhenEveryMemberIsInvalid()
	{
		ClanMember unnamed = member(null, new ClanRank(1));
		when(client.getClanSettings()).thenReturn(settings);
		when(settings.getMembers()).thenReturn(Collections.singletonList(unnamed));
		assertNull(ClanReporter.snapshot(client));
	}
}
