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

> Note: The Maven plugin will overwrite the contents of `gh-pages` with the generated docs; keeping an empty branch is fine.

## Configure GitHub Pages

After `gh-pages` exists, configure Pages:
- Repository Settings > Pages:
  - Source: Deploy from a branch
  - Branch: `gh-pages`, Folder: `/` (root)

GitHub Pages will serve your docs at:
- https://<org-or-user>.github.io/<repo>/

## Publishing the docs

Once the branch exists, publish the docs from your local machine or CI:

```bash
mvn -pl quantum-docs -am -DskipTests deploy
```

What happens:
- Asciidoctor builds HTML/PDF into `quantum-docs/target/site`.
- `maven-scm-publish-plugin` checks out `gh-pages` into `quantum-docs/target/scmpublish`.
- It copies the generated site into that checkout and commits/pushes to `gh-pages`.

## Authentication

The SCM publish plugin pushes via the repository URL configured in the parent POM. Ensure one of the following is set up:
- Your Git is configured with cached HTTPS credentials (Personal Access Token) for `https://github.com/...`.
- Or an SSH-based URL is used and you have your SSH keys configured (adjust `pubScmUrl` if you prefer SSH).

## Troubleshooting

- `fatal: Remote branch gh-pages not found in upstream origin`:
  - Run the initialization script or create the branch via UI once.
- `remote: Not Found` or authentication prompts:
  - Verify you have push permissions and that your PAT/SSH auth is configured locally.
- The plugin tries to clone a URL ending with `/quantum-docs`:
  - This repository’s `pubScmUrl` is configured to the repo root. If you see a module-suffixed URL, ensure `quantum-docs/pom.xml` sets `gh.repo.scm.url` to `${project.parent.scm.developerConnection}` (already done).
- GitHub Pages shows a 404 after deploy:
  - Check Settings > Pages is set to `gh-pages`/root and wait a few minutes for propagation.
