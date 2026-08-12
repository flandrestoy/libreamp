#!/usr/bin/env bash
# Pushes the current branch, triggers a CI build, waits for it, pulls the
# APK locally, then deletes the remote artifact so builds don't accumulate
# storage on GitHub (public repos don't hit the 500MB cap, but no reason to
# leave debris around either).
set -euo pipefail

WORKFLOW="android-build.yml"
DEST="${1:-$PWD/app-debug.apk}"

REPO="$(gh repo view --json nameWithOwner --jq .nameWithOwner)"
BRANCH="$(git rev-parse --abbrev-ref HEAD)"
SHA="$(git rev-parse HEAD)"

echo "Pushing $BRANCH to $REPO..."
git push origin "$BRANCH"

# The push above already triggers the workflow (on: push). Find that run by
# head SHA rather than also dispatching, which would start a second,
# untracked run and leave its artifact behind.
echo "Waiting for the run triggered by $SHA to register..."
RUN_ID=""
for _ in $(seq 1 30); do
  RUN_ID="$(gh run list --repo "$REPO" --workflow "$WORKFLOW" --json databaseId,headSha --jq "[.[] | select(.headSha==\"$SHA\")] | sort_by(.databaseId) | last | .databaseId // empty")"
  [ -n "$RUN_ID" ] && break
  sleep 2
done
if [ -z "$RUN_ID" ]; then
  echo "Timed out waiting for a run for $SHA to start." >&2
  exit 1
fi

echo "Watching run $RUN_ID..."
gh run watch "$RUN_ID" --repo "$REPO" --exit-status

echo "Downloading APK..."
TMPDIR="$(mktemp -d)"
if ! gh run download "$RUN_ID" --repo "$REPO" --dir "$TMPDIR"; then
  echo "Download failed - if this SHA was already pulled by a previous run of this script, its artifact was already deleted. Commit something new and try again." >&2
  rm -rf "$TMPDIR"
  exit 1
fi
APK="$(find "$TMPDIR" -name '*.apk' | head -1)"
if [ -z "$APK" ]; then
  echo "No APK found in the run's artifacts." >&2
  rm -rf "$TMPDIR"
  exit 1
fi
mkdir -p "$(dirname "$DEST")"
cp "$APK" "$DEST"
rm -rf "$TMPDIR"
echo "APK saved to $DEST"

echo "Deleting remote artifact(s)..."
for AID in $(gh api "repos/$REPO/actions/runs/$RUN_ID/artifacts" --jq '.artifacts[].id'); do
  gh api -X DELETE "repos/$REPO/actions/artifacts/$AID" && echo "Deleted artifact $AID"
done

echo "Done."
