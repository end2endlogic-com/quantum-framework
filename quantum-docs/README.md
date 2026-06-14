# Publishing Quantum Docs to GitHub Pages (gh-pages)

This module builds the documentation with Asciidoctor and publishes the generated HTML (target/site) to the `gh-pages` branch using `maven-scm-publish-plugin` during `mvn deploy`.

If you see an error like:

```
fatal: Remote branch gh-pages not found in upstream origin
```

it means the `gh-pages` branch does not exist yet. Create it once, then future deploys will work.

## One-time initialization of gh-pages

You can initialize the `gh-pages` branch in one of two ways.

### Option A: Use the provided script

From the repository root (or from the `quantum-docs` directory), run:

```bash
cd quantum-docs
bash ./scripts/init-gh-pages.sh
```

What this does:
- Creates an orphan branch named `gh-pages` with a minimal commit (`.nojekyll` and README),
- Pushes it to `origin`,
- Returns you to your previous branch.

Prerequisites:
- You have push rights to the GitHub repository.
- Your working tree is clean (no uncommitted changes).

### Option B: Manual CLI steps

```bash
# from repo root
# ensure you have no uncommitted changes

git checkout --orphan gh-pages
# remove tracked files from working tree (they will come back when you checkout your normal branch)
_git_tracked=$(git ls-files); if [ -n "$_git_tracked" ]; then git rm -rf .; fi

touch .nojekyll
printf "# GitHub Pages Branch\n\nManaged by maven-scm-publish-plugin.\n" > README.md

git add .nojekyll README.md
git commit -m "[bootstrap] Initialize gh-pages for docs publishing"

git push -u origin gh-pages

git checkout main   # or your default branch
```

### Option C: GitHub UI

- Go to your repository on GitHub.
- Click the branch dropdown, type `gh-pages`, and choose "Create branch: gh-pages from main" (or similar).
- Then go to Settings > Pages and set:
  - Source: "Deploy from a branch"
  - Branch: `gh-pages` and folder `/` (root)

> Note: **LTS** (1.3.x line) is published to **`gh-pages` root** (default site URL). **1.4.0-SNAPSHOT** preview docs go under **`gh-pages/1.4.0-SNAPSHOT/`** only. See [Publishing the docs](#publishing-the-docs) below.

## Configure GitHub Pages

After `gh-pages` exists, configure Pages:
- Repository Settings > Pages:
  - Source: Deploy from a branch
  - Branch: `gh-pages`, Folder: `/` (root)

GitHub Pages will serve your docs at:
- https://<org-or-user>.github.io/<repo>/

## Publishing the docs

Once the branch exists, build and publish from the **repository root** (or use the same `-pl` paths from `quantum-docs/` if your reactor is set up that way).

The **LTS** build replaces the **root** of `gh-pages` (what visitors see first). The **1.4** build only updates **`1.4.0-SNAPSHOT/`**; that directory is **not** removed when LTS publishes. The versioned `publish-scm` execution is **skipped by default**; activate one of the profiles below.

### Profiles

| Profile | Effect |
|--------|--------|
| `docs-ghpages-1.3.x` or `docs-ghpages-lts` | Publishes the built site to **`gh-pages/` root** (default URL = LTS docs). |
| `docs-ghpages-1.4` | Publishes the built site to **`gh-pages/1.4.0-SNAPSHOT/`** (in-progress / non-default URL). |

### Commands

Build HTML/PDF only (no push to `gh-pages`):

```bash
mvn -pl quantum-docs -DskipTests -Dgpg.skip=true package
```

Publish **LTS** docs to the **site root** — **required:** `git checkout 1.3.x` (branch whose parent POM is **1.3.x**). The **“Version …”** banner comes from **`${project.version}`**; building on a **1.4** branch still produces **1.4** in the HTML even if you use the LTS profile.

```bash
mvn -pl quantum-docs -am -Pdocs-ghpages-1.3.x -DskipTests -Dgpg.skip=true package deploy
```

(`-Pdocs-ghpages-lts` is equivalent to `-Pdocs-ghpages-1.3.x`.)

Publish **1.4.0-SNAPSHOT** preview docs (non-default URL: `…/1.4.0-SNAPSHOT/`):

```bash
mvn -pl quantum-docs -am -Pdocs-ghpages-1.4 -DskipTests -Dgpg.skip=true package deploy
```

What happens on `deploy` (with a profile):

- Asciidoctor builds HTML/PDF into `quantum-docs/target/site` during `package`.
- `maven-scm-publish-plugin` checks out `gh-pages`, copies the site to **root** (LTS) or **`1.4.0-SNAPSHOT/`** (preview), and commits/pushes.

CI: pushes to branches `1.3.x` and `1.4.0-SNAPSHOT` can run the matching profile via `.github/workflows/publish-quantum-docs.yml` (see that file for exact steps).

More detail: [PUBLISHING.md](./PUBLISHING.md).

## Authentication

The SCM publish plugin uses Maven **`server` id `github`** in `~/.m2/settings.xml` (see `quantum-docs/pom.xml`). Alternatively, ensure Git can push to `https://github.com/...` (PAT or SSH, depending on your SCM URL).

Also ensure one of the following is set up if you do not use `settings.xml` for SCM:

- Cached HTTPS credentials (Personal Access Token) for `https://github.com/...`.
- Or an SSH remote and keys (adjust `pubScmUrl` / developer connection if you prefer SSH).

## Troubleshooting

- `fatal: Remote branch gh-pages not found in upstream origin`:
  - Run the initialization script or create the branch via UI once.
- `remote: Not Found` or authentication prompts:
  - Verify you have push permissions and that your PAT/SSH auth is configured locally.
- The plugin tries to clone a URL ending with `/quantum-docs`:
  - This repository’s `pubScmUrl` is configured to the repo root. If you see a module-suffixed URL, ensure `quantum-docs/pom.xml` sets `gh.repo.scm.url` to `${project.parent.scm.developerConnection}` (already done).
- GitHub Pages shows a 404 after deploy:
  - Check Settings > Pages is set to `gh-pages`/root and wait a few minutes for propagation.
