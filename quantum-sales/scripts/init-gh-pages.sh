#!/usr/bin/env bash
set -euo pipefail

# Initialize the gh-pages branch for GitHub Pages publishing (one-time setup)
# Usage:
#   cd quantum-sales
#   bash ./scripts/init-gh-pages.sh
#
# Preconditions:
#   - You have push access to origin (GitHub)
#   - Your working tree is clean (no uncommitted changes)
#   - "origin" remote points to https://github.com/<org>/<repo>.git

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "This script must be run inside a git repository (cd to the repository root or to quantum-sales)." >&2
  exit 1
fi

# Move to repo root if run from quantum-sales/
REPO_ROOT=$(git rev-parse --show-toplevel)
cd "$REPO_ROOT"

# Check if gh-pages already exists remotely
if git ls-remote --exit-code --heads origin gh-pages >/dev/null 2>&1; then
  echo "Remote branch 'gh-pages' already exists on origin. Nothing to do."
  exit 0
fi

# Ensure clean working tree
if [ -n "$(git status --porcelain)" ]; then
  echo "Working tree is not clean. Please commit or stash your changes before running this script." >&2
  exit 1
fi

CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD)

echo "Creating orphan branch 'gh-pages'..."
# Create an orphan branch (no history)
git checkout --orphan gh-pages

# Remove tracked files from the working tree (we will add just a placeholder)
# This does not affect other branches; when you switch back, files will reappear from that branch
if [ -n "$(git ls-files)" ]; then
  git rm -rf . >/dev/null 2>&1 || true
fi

# Create minimal contents for the pages branch
cat > README.md <<'EOF'
# GitHub Pages Branch

This branch is managed by the Maven SCM Publish Plugin (`maven-scm-publish-plugin`).
Do not edit files here manually. Run this from the default branch instead:

    mvn -am -DskipTests deploy

That command builds the docs to `target/site` and publishes those files to `gh-pages`.
EOF

touch .nojekyll

git add .nojekyll README.md

git commit -m "[bootstrap] Initialize gh-pages for docs publishing"

echo "Pushing 'gh-pages' to origin..."
git push -u origin gh-pages

echo "Switching back to '$CURRENT_BRANCH'..."
git checkout "$CURRENT_BRANCH"

echo "Done. The 'gh-pages' branch now exists on origin. You can publish docs with:"
echo "  mvn -am -DskipTests deploy"

