# Publishing quantum-docs to GitHub Pages (`gh-pages`)

**LTS (1.3.x)** is published to **`gh-pages` root** — that is the default documentation URL visitors see.

**1.4.0-SNAPSHOT** (in progress) is published only under **`gh-pages/1.4.0-SNAPSHOT/`**.

`maven-scm-publish-plugin` uses **`ignorePathsToDelete`** so that when LTS is pushed to the root, the **`1.4.0-SNAPSHOT/`** directory is not removed.

## Profiles

| Profile | Purpose |
|--------|---------|
| `docs-ghpages-1.3.x` or `docs-ghpages-lts` | Empty `docs.ghpages.subdir` → publish to **root**; enables `publish-scm` on `deploy`. |
| `docs-ghpages-1.4` | `docs.ghpages.subdir` = `1.4.0-SNAPSHOT` → publish preview under that folder only. |

By default, versioned `publish-scm` is **skipped** unless you activate one of these profiles.

## Version line in the HTML (“Version …” on every page)

The banner uses Asciidoctor **`revnumber`** = **`${project.version}`** in `quantum-docs/pom.xml` (Maven reactor version, not the profile name).

- For **correct LTS text** at the root URL: build on git branch **`1.3.x`** (POM still **1.3.x**), then `-Pdocs-ghpages-1.3.x` (or `-Pdocs-ghpages-lts`).
- For **correct 1.4 preview text**: build on **`1.4.0-SNAPSHOT`**, then `-Pdocs-ghpages-1.4`.

## URLs

- **LTS (default):** `https://<org>.github.io/<repo>/` (and paths under it, e.g. `user-guide/index.html`).
- **1.4 preview:** `https://<org>.github.io/<repo>/1.4.0-SNAPSHOT/…`

## Examples

Build HTML/PDF only (no deploy):

```bash
mvn -pl quantum-docs -DskipTests -Dgpg.skip=true package
```

Publish **LTS** to site root (from branch `1.3.x`):

```bash
mvn -pl quantum-docs -am -Pdocs-ghpages-1.3.x -DskipTests -Dgpg.skip=true package deploy
```

Publish **1.4.0-SNAPSHOT** preview:

```bash
mvn -pl quantum-docs -am -Pdocs-ghpages-1.4 -DskipTests -Dgpg.skip=true package deploy
```

SCM authentication: Maven `settings.xml`, e.g. `<server><id>github</id>…` matching `quantum-docs/pom.xml`.
