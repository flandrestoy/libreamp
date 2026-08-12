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

echo "Pushing $BRANCH to $REPO..."
git push origin "$BRANCH"

LAST_ID="$(gh run list --repo "$REPO" --workflow "$WORKFLOW" --limit 1 --json databaseId --jq '.[0].databaseId // 0')"

echo "Dispatching build..."
gh workflow run "$WORKFLOW" --repo "$REPO" --ref "$BRANCH"

echo "Waiting for the new run to register..."
RUN_ID="$LAST_ID"
for _ in $(seq 1 30); do
  sleep 2
  RUN_ID="$(gh run list --repo "$REPO" --workflow "$WORKFLOW" --limit 1 --json databaseId --jq '.[0].databaseId')"
  [ "$RUN_ID" != "$LAST_ID" ] && break
done
if [ "$RUN_ID" == "$LAST_ID" ]; then
  echo "Timed out waiting for a new run to start." >&2
  exit 1
fi

echo "Watching run $RUN_ID..."
gh run watch "$RUN_ID" --repo "$REPO" --exit-status

echo "Downloading APK..."
TMPDIR="$(mktemp -d)"
gh run download "$RUN_ID" --repo "$REPO" --dir "$TMPDIR"
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
