# Quantum Framework Documentation

This module builds the Quantum documentation (HTML and PDF) using Asciidoctor via Maven. The structure is similar to Spring’s reference docs and includes a user guide and tutorials targeted at mid‑level Java developers.

## Prerequisites
- Java 17 (GraalVM or compatible JDK)
- Maven 3.9+

## Generating Documentation:
```
mvn -pl quantum-docs -am -DskipTests -DskipITs=true clean package
```

## Build with Maven

Build only the docs module (recommended):

```
# from repository root (framework/)
mvn -pl quantum-docs -am -DskipTests clean package
```

Outputs are generated here:
- HTML: `quantum-docs/target/docs/index.html`
- PDF: `quantum-docs/target/docs/index.pdf`

You can also build as part of the full reactor:

```
mvn -DskipTests clean package
```

## Build with Docker (Asciidoctor) – optional
If you don’t have Java/Maven locally, you can use the Asciidoctor Docker image to render AsciiDoc directly. Note that this bypasses the Maven plugin configuration and produces outputs to a chosen directory.

```
cd quantum-docs
mkdir -p target/docker-docs

docker run --rm \
  -v "$PWD/src/docs/asciidoc":/documents \
  -v "$PWD/target/docker-docs":/out \
  asciidoctor/docker-asciidoctor \
  bash -lc "asciidoctor -r asciidoctor-pdf -b pdf -D /out index.adoc && asciidoctor -D /out index.adoc"
```

Outputs will be under `quantum-docs/target/docker-docs/`.

## Contents
- Guides: SaaS and multi‑tenancy, tenant models (per‑DB or shared DB), modeling with Functional Areas/Domains/Actions, DomainContext/RuleContext/DataDomain, REST CRUD, authentication (JWT and pluggable providers).
- Tutorial: Supply chain collaboration end‑to‑end example (models, repos, resources, sharing rules), including guidance on separating model/repo modules for use with orchestration/workflows (e.g., Temporal, Windmill).

## Notes
- The Asciidoctor Maven plugin is configured to write outputs into `target/docs/` during the `prepare-package` phase.
- PDF generation uses `asciidoctorj-pdf`; no extra system fonts are required with the default theme. Add fonts to `src/docs/fonts` and adjust the Maven `pdf-fontsdir` attribute if needed.
