# discord-role-sync

A RuneLite plugin that links your Old School RuneScape account to Discord and reports your clan
roster so clan ranks sync to Discord roles.

It talks to a backend over HTTP; there is no public OSRS clan API, so the in-client clan data this
plugin reads is the only source of truth for membership and ranks. The default backend
(`https://viggora.app`) is configurable — you can point the plugin at your own deployment.

## What it does

1. **Link your account.** Run `/link` in the Discord server (the bot replies with a one-time
   access key). Paste the key into the plugin and toggle **Link this account** while logged in. The
   plugin sends your account hash + RSN to `POST /api/plugin/redeem`, completing the link, and stores
   a long-lived **report token** returned by the backend.

2. **Report your clan roster.** While the token is present, the plugin periodically (and whenever the
   clan loads) reads `ClanSettings.getMembers()` — every member's name, `ClanRank` value, and title —
   and sends it to `POST /api/plugin/clan`.

You don't designate a trusted reporter: **every member's plugin reports the full roster it sees**, and
the backend accepts a rank only when a quorum of members corroborate it. One client can't spoof a
rank, so clan reporting is safe to leave on for everyone.

## The two requests

| Request | When | Body |
| --- | --- | --- |
| `POST /api/plugin/redeem` | you toggle **Link this account** | `{ key, accountHash, displayName }` → `{ …, reportToken }` |
| `POST /api/plugin/clan` | clan loads + every 10 min | `{ reportToken, clanName, members: [{ name, rank, title }] }` |

`rank` is the RuneLite `ClanRank.getRank()` value (−1…127; higher = more senior).

## Settings

| Setting | Purpose |
| --- | --- |
| Backend URL | Backend base URL (default `https://viggora.app`). |
| Access key | The one-time key from the Discord `/link` command. |
| Link this account | Toggle on (while logged in) to link the current account; resets itself. |
| Report clan roster | Periodically report your clan's roster for rank sync (on by default). |

The report token is stored internally (not shown) and re-issued each time you link.

## Building / running

Standard RuneLite external-plugin setup (JDK 11). Build with your local Gradle:

```bash
gradle build
```

To run RuneLite with the plugin loaded for development, run the `main()` in
`src/test/java/com/discordrolesync/DiscordRoleSyncPluginTest.java`. See the official
[example-plugin](https://github.com/runelite/example-plugin) for the canonical toolchain and for
submitting to the Plugin Hub.

### Building against upstream RuneLite

To build and run against RuneLite's `master` (newer than the latest published release), use:

```bash
scripts/build-upstream.sh
```

It clones RuneLite, runs its `./gradlew publishAllToMavenLocal` to install the client into your local
Maven repo (`~/.m2`), builds this plugin against that exact version (`-PruneLiteVersion=…`), and
copies the jar into `~/.runelite/sideloaded-plugins/` so the client side-loads it on next launch.
Requires JDK 11 (it uses RuneLite's own Gradle wrapper, so no separate Gradle/Maven install is
needed). Useful flags: `--branch <ref>` to pick a different RuneLite ref, `--skip-runelite` to reuse
an already-published build, and `--no-install` to build without copying. Run with `--help` for the
full list.

## Releasing to the Plugin Hub

`scripts/submit-plugin-hub.sh` automates the [plugin-hub](https://github.com/runelite/plugin-hub)
submission flow: it branches off `runelite/plugin-hub` `master` in your fork, writes
`plugins/discord-role-sync` (the `repository=` + `commit=` marker) pointing at the released commit,
force-pushes the branch, and opens or updates a PR against upstream.

The **Submit to Plugin Hub** GitHub Action (`.github/workflows/submit-to-plugin-hub.yml`) runs it
automatically **whenever a tag is pushed** — the tagged commit becomes the `commit=` value. It needs
a `PLUGIN_HUB_TOKEN` repo secret (a PAT that can push to your `plugin-hub` fork and open PRs on
`runelite/plugin-hub`); the default `GITHUB_TOKEN` is scoped to this repo and won't work. So a
release is just:

```bash
git tag v1.0.0 && git push origin v1.0.0
```

You can also run the script locally (using your own `gh auth login` session) to submit by hand.
