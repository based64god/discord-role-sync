#!/usr/bin/env bash
#
# Build discord-role-sync against RuneLite built from upstream source (master/main), then
# install the resulting plugin jar into RuneLite's sideloaded-plugins directory.
#
# What it does:
#   1. Clone/update the upstream RuneLite repo (default: master).
#   2. Run RuneLite's `./gradlew publishAllToMavenLocal`, installing its client (+ runelite-api,
#      cache, jshell) into your local Maven repo (~/.m2).
#   3. Build this plugin's jar against that exact version.
#   4. Copy the jar into ~/.runelite/sideloaded-plugins/ so the client loads it on next launch.
#
# Usage:
#   scripts/build-upstream.sh [options]
#
# Options:
#   -b, --branch <ref>      RuneLite branch/tag/commit to build (default: $RUNELITE_BRANCH or master)
#   -s, --src <dir>         Where to clone/keep the RuneLite source (default: ~/.cache/runelite-upstream)
#       --repo <url>        RuneLite git remote (default: https://github.com/runelite/runelite.git)
#       --skip-runelite     Reuse the RuneLite build already in ~/.m2 (skip clone + publish)
#       --no-install        Build the jar but do not copy it into sideloaded-plugins
#   -h, --help              Show this help
#
# Environment overrides:
#   GRADLE_CMD    Gradle for THIS plugin (default: ./gradlew here, else system gradle, else
#                 the gradlew from the RuneLite checkout)
#   RUNELITE_DIR  RuneLite home          (default: ~/.runelite) — jar installs to its sideloaded-plugins/
#
set -euo pipefail

# --- defaults ---------------------------------------------------------------
REPO_URL="https://github.com/runelite/runelite.git"
BRANCH="${RUNELITE_BRANCH:-master}"
SRC_DIR="${RUNELITE_SRC:-$HOME/.cache/runelite-upstream}"
SKIP_RUNELITE=0
DO_INSTALL=1
PLUGIN_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNELITE_HOME="${RUNELITE_DIR:-$HOME/.runelite}"
M2_CLIENT="${HOME}/.m2/repository/net/runelite/client"

# --- pretty logging ---------------------------------------------------------
log()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33mwarning:\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

# --- arg parsing ------------------------------------------------------------
while [ $# -gt 0 ]; do
	case "$1" in
		-b|--branch)       BRANCH="${2:?--branch needs a value}"; shift 2;;
		-s|--src)          SRC_DIR="${2:?--src needs a value}"; shift 2;;
		--repo)            REPO_URL="${2:?--repo needs a value}"; shift 2;;
		--skip-runelite)   SKIP_RUNELITE=1; shift;;
		--no-install)      DO_INSTALL=0; shift;;
		-h|--help)         awk 'NR>1 && /^#/ {sub(/^# ?/,""); print; next} NR>1 {exit}' "${BASH_SOURCE[0]}"; exit 0;;
		*)                 die "unknown option: $1 (try --help)";;
	esac
done

# --- prerequisites ----------------------------------------------------------
command -v git  >/dev/null || die "git not found on PATH"
command -v java >/dev/null || die "java not found on PATH (RuneLite + this plugin need JDK 11)"

# --- 1 & 2: build + publish RuneLite from source to ~/.m2 -------------------
if [ "$SKIP_RUNELITE" -eq 1 ]; then
	log "Skipping RuneLite source build (--skip-runelite); using whatever is already in ~/.m2"
else
	if [ -d "$SRC_DIR/.git" ]; then
		log "Updating RuneLite source in $SRC_DIR (branch: $BRANCH)"
		git -C "$SRC_DIR" fetch --depth 1 origin "$BRANCH"
		git -C "$SRC_DIR" checkout -q FETCH_HEAD
	else
		log "Cloning RuneLite ($BRANCH) into $SRC_DIR"
		mkdir -p "$(dirname "$SRC_DIR")"
		git clone --depth 1 --branch "$BRANCH" "$REPO_URL" "$SRC_DIR"
	fi

	[ -x "$SRC_DIR/gradlew" ] || die "no gradlew in the RuneLite checkout ($SRC_DIR) — unexpected for upstream"
	log "Publishing RuneLite to ~/.m2 (./gradlew publishAllToMavenLocal — slow the first time)"
	( cd "$SRC_DIR" && ./gradlew --console=plain publishAllToMavenLocal )
fi

# --- resolve Gradle for building THIS plugin --------------------------------
# Prefer the plugin's own wrapper, then GRADLE_CMD/system gradle, then fall back to the
# gradlew we just cloned with RuneLite (handy when no Gradle is installed locally).
if [ -n "${GRADLE_CMD:-}" ]; then
	:
elif [ -x "$PLUGIN_DIR/gradlew" ]; then
	GRADLE_CMD="$PLUGIN_DIR/gradlew"
elif command -v gradle >/dev/null; then
	GRADLE_CMD="gradle"
elif [ -x "$SRC_DIR/gradlew" ]; then
	GRADLE_CMD="$SRC_DIR/gradlew"
	warn "no local Gradle found; using the RuneLite checkout's gradlew to build the plugin"
else
	die "no Gradle found. Install Gradle, add a wrapper (gradle wrapper), or set GRADLE_CMD=/path/to/gradle"
fi

# --- determine the RuneLite version that was published ----------------------
[ -d "$M2_CLIENT" ] || die "no RuneLite client in $M2_CLIENT — did the publish step run? (drop --skip-runelite)"
# Newest version directory by mtime = the one we just published.
RL_VERSION="$(ls -1t "$M2_CLIENT" | grep -vE 'maven-metadata' | head -1 || true)"
[ -n "$RL_VERSION" ] || die "could not determine the published RuneLite version under $M2_CLIENT"
log "Building plugin against RuneLite version: $RL_VERSION"

# --- 3: build the plugin jar ------------------------------------------------
log "Running Gradle build ($GRADLE_CMD)"
( cd "$PLUGIN_DIR" && "$GRADLE_CMD" --console=plain clean jar -PruneLiteVersion="$RL_VERSION" )

# Pick the plugin jar (exclude -sources/-javadoc).
JAR="$(ls -t "$PLUGIN_DIR"/build/libs/*.jar 2>/dev/null | grep -vE -- '-(sources|javadoc)\.jar$' | head -1 || true)"
[ -n "$JAR" ] && [ -f "$JAR" ] || die "build succeeded but no jar found under build/libs/"
log "Built: $JAR"

# --- 4: install into sideloaded-plugins -------------------------------------
if [ "$DO_INSTALL" -eq 1 ]; then
	DEST="$RUNELITE_HOME/sideloaded-plugins"
	mkdir -p "$DEST"
	# Remove any older copy of this plugin so duplicates don't pile up.
	rm -f "$DEST"/discord-role-sync-*.jar
	cp "$JAR" "$DEST/"
	log "Installed to: $DEST/$(basename "$JAR")"
	echo
	log "Done. Start RuneLite from this same upstream build and enable \"Discord Role Sync\" in the plugin list."
else
	echo
	log "Done (jar not installed; --no-install). Copy it yourself with:"
	echo "    cp '$JAR' '$RUNELITE_HOME/sideloaded-plugins/'"
fi
