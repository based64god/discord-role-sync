package com.discordrolesync;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.runelite.api.Client;
import net.runelite.api.clan.ClanMember;
import net.runelite.api.clan.ClanRank;
import net.runelite.api.clan.ClanSettings;
import net.runelite.api.clan.ClanTitle;

/**
 * Reads the in-client clan roster. There is no public OSRS clan API, so this is the only source of
 * clan membership + ranks — and it must be read on the client thread.
 */
final class ClanReporter
{
	/** A single roster entry: RSN, the numeric ClanRank value (-1..127), and the rank's title. */
	static final class Member
	{
		final String name;
		final int rank;
		@Nullable
		final String title;

		Member(String name, int rank, @Nullable String title)
		{
			this.name = name;
			this.rank = rank;
			this.title = title;
		}
	}

	static final class Snapshot
	{
		final String clanName;
		final List<Member> members;

		Snapshot(String clanName, List<Member> members)
		{
			this.clanName = clanName;
			this.members = members;
		}
	}

	private ClanReporter()
	{
	}

	/**
	 * Snapshot the roster of the clan the local player belongs to. Must be called on the client
	 * thread. Returns {@code null} when no clan is loaded.
	 */
	@Nullable
	static Snapshot snapshot(Client client)
	{
		final ClanSettings settings = client.getClanSettings();
		if (settings == null)
		{
			return null;
		}

		final List<ClanMember> roster = settings.getMembers();
		if (roster == null || roster.isEmpty())
		{
			return null;
		}

		final List<Member> members = new ArrayList<>(roster.size());
		for (ClanMember m : roster)
		{
			if (m == null || m.getName() == null)
			{
				continue;
			}
			final ClanRank rank = m.getRank();
			final int rankValue = rank != null ? rank.getRank() : -1;
			final ClanTitle title = rank != null ? settings.titleForRank(rank) : null;
			members.add(new Member(m.getName(), rankValue, title != null ? title.getName() : null));
		}

		if (members.isEmpty())
		{
			return null;
		}
		return new Snapshot(settings.getName(), members);
	}
}
