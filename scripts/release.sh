#!/usr/bin/env bash
# scripts/release.sh — cut a new versioned release.
#
# Usage:
#     scripts/release.sh 0.1.1
#
# Does, in order:
#   1. Validates the new version is well-formed semver and higher than the
#      current one.
#   2. Checks that CHANGELOG.md has a matching `## v<version>` section —
#      bail out if it doesn't, so a release never ships without notes.
#   3. Bumps the VERSION file.
#   4. Commits the bump with a clean release message.
#   5. Tags `v<version>` and pushes the tag, which triggers
#      .github/workflows/release.yml to build APKs + publish the release.
#
# Hold-and-release: don't run this on every change. Accumulate fixes on
# main, then cut a release when there's a coherent batch to ship.

set -euo pipefail

if [ "${1:-}" = "" ] || [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
  cat <<USAGE
Usage: scripts/release.sh <new-version>

  <new-version> must be MAJOR.MINOR.PATCH semver (e.g. 0.1.1).

Before running:
  • Append a '## v<new-version>' section to the top of CHANGELOG.md
    with plain-English user-facing bullets.
  • Make sure 'main' is up to date and has everything you want shipped.

After running:
  • Watch the build at https://github.com/Ben-Timan-Smartech/screens-app-v2/actions
  • When green, the APKs + release notes will be at
    https://github.com/Ben-Timan-Smartech/screens-app-v2/releases/latest
USAGE
  exit 0
fi

NEW="$1"

if ! printf '%s' "$NEW" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; then
  echo "error: '$NEW' is not MAJOR.MINOR.PATCH semver." >&2
  exit 1
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

CURRENT="$(tr -d '[:space:]' < VERSION)"
if [ "$CURRENT" = "$NEW" ]; then
  echo "error: VERSION already says $NEW — nothing to bump." >&2
  exit 1
fi

# Refuse to release downward. Sorting trick (semver-ish): compare by
# field, prefer the higher one.
HIGHER=$(printf '%s\n%s\n' "$CURRENT" "$NEW" | sort -V | tail -1)
if [ "$HIGHER" != "$NEW" ]; then
  echo "error: $NEW is not higher than current $CURRENT." >&2
  exit 1
fi

if ! grep -q "^## v$NEW\$" CHANGELOG.md; then
  echo "error: no '## v$NEW' section in CHANGELOG.md." >&2
  echo "       add a section with this release's notes first." >&2
  exit 1
fi

# Refuse to release with a dirty tree — otherwise we'd commit unrelated
# half-finished work alongside the version bump.
if ! git diff --quiet || ! git diff --cached --quiet; then
  echo "error: working tree is dirty. Commit or stash before releasing." >&2
  git status --short
  exit 1
fi

# Refuse if the tag already exists locally or on origin.
if git rev-parse "v$NEW" >/dev/null 2>&1; then
  echo "error: tag v$NEW already exists locally." >&2
  exit 1
fi
if git ls-remote --exit-code --tags origin "refs/tags/v$NEW" >/dev/null 2>&1; then
  echo "error: tag v$NEW already exists on origin." >&2
  exit 1
fi

echo "Releasing v$NEW (was v$CURRENT)…"
echo "$NEW" > VERSION

git add VERSION
git -c user.email="ben@smartechworld.com" -c user.name="Ben Timan" \
    commit -m "Release v$NEW"

git tag -a "v$NEW" -m "Release v$NEW"

# Push commit + tag in one go so the workflow trigger and the commit
# the workflow expects to see arrive together.
git push origin HEAD "refs/tags/v$NEW"

echo
echo "Pushed v$NEW. Build status:"
echo "  https://github.com/Ben-Timan-Smartech/screens-app-v2/actions/workflows/release.yml"
echo "Release will appear at:"
echo "  https://github.com/Ben-Timan-Smartech/screens-app-v2/releases/tag/v$NEW"
