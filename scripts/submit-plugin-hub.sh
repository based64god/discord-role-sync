#!/usr/bin/env bash
#
# Submit / update this plugin on the RuneLite Plugin Hub.
#
# Follows the plugin-hub's documented flow (https://github.com/runelite/plugin-hub):
#   1. Clone your fork of runelite/plugin-hub.
#   2. Branch off the latest upstream/master.
#   3. Write plugins/<plugin-name> with this repo's URL + the commit being released.
#   4. Force-push the branch to your fork.
#   5. Open (or update) a PR against runelite/plugin-hub.
#
# Designed to run in CI on a tag push, but also works locally.
#
# Auth: needs a GitHub token (env GH_TOKEN or GITHUB_TOKEN) that can push to your fork and
# open PRs on runelite/plugin-hub. In GitHub Actions the default GITHUB_TOKEN is NOT enough
# (it is scoped to this repo) — use a Personal Access Token stored as a secret. Locally, an
# existing `gh auth login` session works too.
#
# Config (env vars, all optional — sensible defaults derived from this repo):
#   PLUGIN_HUB_FORK      owner/repo of your plugin-hub fork   (default: <owner>/plugin-hub)
#   PLUGIN_HUB_UPSTREAM  upstream plugin-hub                  (default: runelite/plugin-hub)
#   PLUGIN_NAME          manifest file name in plugins/       (default: rootProject.name)
#   PLUGIN_REPO_URL      this plugin's git URL for the marker (default: derived from origin)
#   PLUGIN_COMMIT        full 40-char commit to publish       (default: GITHUB_SHA or HEAD)
#   PLUGIN_TAG           tag/version, for branch + PR text    (default: GITHUB_REF_NAME or git describe)
#   GIT_AUTHOR_NAME / GIT_AUTHOR_EMAIL   committer identity in the fork
#
set -euo pipefail

PLUGIN_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

log()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

command -v git >/dev/null || die "git not found on PATH"
command -v gh  >/dev/null || die "gh (GitHub CLI) not found on PATH"

# --- derive configuration ---------------------------------------------------
UPSTREAM="${PLUGIN_HUB_UPSTREAM:-runelite/plugin-hub}"

# This plugin's GitHub repo, as owner/repo and as an https .git URL.
if [ -n "${GITHUB_REPOSITORY:-}" ]; then
	SELF_SLUG="$GITHUB_REPOSITORY"
else
	_origin="$(git -C "$PLUGIN_DIR" remote get-url origin)"
	_origin="${_origin#git@github.com:}"
	_origin="${_origin#https://github.com/}"
	SELF_SLUG="${_origin%.git}"
fi
SELF_OWNER="${SELF_SLUG%%/*}"
REPO_URL="${PLUGIN_REPO_URL:-https://github.com/${SELF_SLUG}.git}"

FORK="${PLUGIN_HUB_FORK:-${SELF_OWNER}/plugin-hub}"
FORK_OWNER="${FORK%%/*}"

# Plugin name = the marker filename in plugins/. Defaults to gradle's rootProject.name.
PLUGIN_NAME="${PLUGIN_NAME:-$(sed -n "s/.*rootProject\.name *= *['\"]\([^'\"]*\)['\"].*/\1/p" "$PLUGIN_DIR/settings.gradle")}"
[ -n "$PLUGIN_NAME" ] || die "could not determine PLUGIN_NAME (set it explicitly)"

# The commit users will build: the tagged commit in CI, else current HEAD.
COMMIT="${PLUGIN_COMMIT:-${GITHUB_SHA:-$(git -C "$PLUGIN_DIR" rev-parse HEAD)}}"
[ "${#COMMIT}" -eq 40 ] || die "PLUGIN_COMMIT must be a full 40-char hash, got: '$COMMIT'"

TAG="${PLUGIN_TAG:-${GITHUB_REF_NAME:-$(git -C "$PLUGIN_DIR" describe --tags --exact-match 2>/dev/null || echo "${COMMIT:0:10}")}}"
BRANCH="$PLUGIN_NAME"   # plugin-hub convention: one long-lived branch per plugin, force-pushed.

log "Plugin:    $PLUGIN_NAME ($REPO_URL)"
log "Commit:    $COMMIT"
log "Tag:       $TAG"
log "Fork:      $FORK   ->   upstream $UPSTREAM"

# --- ensure gh is authed and git can push via it ----------------------------
gh auth status >/dev/null 2>&1 || die "gh is not authenticated (set GH_TOKEN/GITHUB_TOKEN or run 'gh auth login')"
gh auth setup-git >/dev/null 2>&1 || true

# Make sure the fork exists; create it from upstream if it doesn't.
if ! gh repo view "$FORK" >/dev/null 2>&1; then
	log "Fork $FORK not found — creating it from $UPSTREAM"
	gh repo fork "$UPSTREAM" --fork-name "${FORK#*/}" --clone=false
fi

# --- clone fork, branch off upstream/master (per plugin-hub instructions) ----
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
log "Cloning $FORK"
git clone --quiet "https://github.com/${FORK}.git" "$WORK"
cd "$WORK"

git config user.name  "${GIT_AUTHOR_NAME:-${GITHUB_ACTOR:-plugin-hub-bot}}"
git config user.email "${GIT_AUTHOR_EMAIL:-${GITHUB_ACTOR:-plugin-hub-bot}@users.noreply.github.com}"

git remote add upstream "https://github.com/${UPSTREAM}.git"
git fetch --quiet upstream master
git checkout -q -B "$BRANCH" upstream/master

# New submission vs. update? (does the marker already exist upstream?)
ACTION="Add"
[ -f "plugins/$PLUGIN_NAME" ] && ACTION="Update"

# --- write the marker -------------------------------------------------------
mkdir -p plugins
printf 'repository=%s\ncommit=%s\n' "$REPO_URL" "$COMMIT" > "plugins/$PLUGIN_NAME"
log "Wrote plugins/$PLUGIN_NAME:"
sed 's/^/    /' "plugins/$PLUGIN_NAME"

if git diff --quiet -- "plugins/$PLUGIN_NAME"; then
	log "Marker already points at $COMMIT upstream — nothing to do."
	exit 0
fi

git add "plugins/$PLUGIN_NAME"
git commit -q -m "$ACTION $PLUGIN_NAME ($TAG)"

log "Pushing branch '$BRANCH' to $FORK"
git push -q -f -u origin "$BRANCH"

# --- open or update the PR --------------------------------------------------
existing="$(gh pr list --repo "$UPSTREAM" --state open \
	--json url,headRefName,headRepositoryOwner \
	--jq "map(select(.headRefName==\"$BRANCH\" and .headRepositoryOwner.login==\"$FORK_OWNER\"))[0].url" \
	2>/dev/null || true)"

if [ -n "$existing" ] && [ "$existing" != "null" ]; then
	log "PR already open — the force-push updated it: $existing"
else
	title="$ACTION $PLUGIN_NAME"
	[ "$ACTION" = "Update" ] && title="$ACTION $PLUGIN_NAME to $TAG"
	body=$(printf '%s the **%s** plugin marker to commit \`%s\` (%s).\n\nRepository: %s\n' \
		"$ACTION" "$PLUGIN_NAME" "$COMMIT" "$TAG" "$REPO_URL")
	url="$(gh pr create --repo "$UPSTREAM" --base master --head "${FORK_OWNER}:${BRANCH}" \
		--title "$title" --body "$body")"
	log "Opened PR: $url"
fi
