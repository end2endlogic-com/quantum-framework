# Split Package Remediation

Quarkus/Jandex reports **split package** warnings when the same package name appears in more than one JAR. Split packages are bad practice (especially for JPMS) and can cause class-loading ambiguity.

**Backward compatibility:** Any remediation (moves/renames) must preserve compatibility for existing applications. Prefer consolidating classes into a single module under the same package name (no API or import changes for consumers). Avoid renaming packages or moving types to new package names unless callers are updated in the same release; otherwise document as breaking and schedule for a major version.

## Current split packages (Quantum Framework)

| Package | Archives | Remediation |
|---------|----------|-------------|
| `com.e2eq.framework.util` | quantum-framework, quantum-models, quantum-util | **Option A:** Move all util classes into `quantum-util` and have framework/models depend on it. **Option B:** Rename per-module: e.g. `quantum-framework` → `com.e2eq.framework.framework.util`, `quantum-models` → `com.e2eq.framework.models.util`, keep `quantum-util` as `com.e2eq.framework.util`. Prefer Option A (single canonical util module). |
| `com.e2eq.framework.annotations` | quantum-framework, quantum-models | Keep only in **quantum-models** (FunctionalAction, FunctionalMapping, support). Remove or move any annotation in quantum-framework to quantum-models or a dedicated `com.e2eq.framework.framework.annotations` package. |
| `com.e2eq.framework.query` | quantum-framework, quantum-models | Keep only in **quantum-framework** (QueryToPredicateJsonListener). Ensure quantum-models does not define `com.e2eq.framework.query`; if it does, move to e.g. `com.e2eq.framework.models.query`. |
| `com.e2eq.framework.securityrules` | quantum-models, quantum-morphia-repos | **Option A:** Move RuleContext, RuleIndex, AccessListResolver from quantum-morphia-repos into quantum-models (or a new quantum-securityrules module). **Option B:** Move RuleExpander from quantum-models into quantum-morphia-repos and keep a single securityrules package there. Prefer one module owning `com.e2eq.framework.securityrules` and the other depending on it. |

## Third-party split package

| Package | Archives | Remediation |
|---------|----------|-------------|
| `com.github.fge.jackson` | jackson-coreutils-equivalence, jackson-coreutils | Upstream split; we cannot fix. Options: (1) Exclude one of the two dependencies if not needed; (2) Add a note in docs; (3) Use `quarkus.jandex.include-all=false` and explicit index only for the JAR we need (risky). |

## Recommended order of work

1. **securityrules** – Move morphia-repos securityrules classes into quantum-models (or new module) so one JAR owns the package.
2. **util** – Consolidate in quantum-util: move `CSVImportHelper`, `CSVExportHelper` from quantum-framework and `SecurityUtils` from quantum-models into quantum-util; update imports.
3. **annotations** – Ensure only quantum-models contains `com.e2eq.framework.annotations`; move or rename any in quantum-framework.
4. **query** – Ensure only quantum-framework contains `com.e2eq.framework.query`; move or rename any in quantum-models.

After each change, run `mvn clean compile` and check that the split-package warning for that package is gone.
