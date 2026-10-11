# Secrets Provider SPI: pluggable secret stores and key providers

Status: **Draft for review** (2026-10-11)
Module: `quantum-secrets`
Related: `SecretResolver` (`${secret.vault://ref}` read contract), `SecretEncryptor`, `ManagedSecret`

## 1. Problem

Applications should be able to keep tenant secrets in whatever their cloud provides (AWS Secrets
Manager, GCP Secret Manager, Azure Key Vault, HashiCorp Vault, or our own encrypted Mongo rows),
and change that by configuration, not code.

Today there is one implementation and no seam:

- Secret values are always `ManagedSecret` rows in the realm's Mongo, AES-256-GCM encrypted with a
  KEK read from `quantum.secrets.kek.v<N>` (an environment variable in practice).
- `SecretResolver` is a read contract (`resolve(realm, ref)`), but it is hard-wired to that row
  plus `SecretEncryptor`. `ManagedSecret.providerType` is stored and shown, but nothing dispatches on it.
- There is no write contract. Every caller that stores a secret builds a `ManagedSecret`, calls
  `SecretEncryptor.encrypt`, and saves through `ManagedSecretRepo` itself, and most callers that read
  one decrypt it themselves too.

So moving a deployment to a cloud secret manager would touch every caller in every application, and
all tenants share one master key held in an environment variable.

## 2. Goals and non-goals

Goals
- **One API for applications**: resolve, store, delete and rotate secrets by `(realm, refName)`.
  Applications never touch ciphertext, IVs, key versions or a store SDK.
- **Pluggable secret stores**, selected by configuration: `managed` (today's encrypted rows, the
  default), `aws-secrets-manager`, `gcp-secret-manager`, `azure-key-vault`, `hashicorp-vault`.
- **Pluggable key providers** for the `managed` store: `config` (today's KEK), `aws-kms`, `gcp-kms`,
  `azure-key-vault`. With a KMS provider the master key never leaves the KMS, and there is no
  environment variable.
- **No change for existing references**: `${secret.vault://ref}` keeps working and keeps meaning
  "this realm's secret named ref".
- **Realm isolation preserved**: resolution stays realm-explicit with no cross-realm fallback, and
  every store namespaces by realm.
- **Fail closed**: a missing store, missing keys, an unreachable provider or an unknown reference is a
  typed error, never a plaintext fallback or an empty value.

Non-goals
- Changing the security framework (principals, resource contexts, rules). Writes keep using the
  existing APIs exactly as callers do today.
- A secrets UI. The existing `/settings/secrets` resource keeps its contract and moves onto the new API.
- Any form of background polling or scheduled sync of external stores (see §6.6).

## 3. Current usage (inventory, 2026-10-11)

quantum-framework
- `quantum-secrets`: `ManagedSecretResource` (CRUD, `{ref}/value`, `rotate-keys`),
  `SecretsMigrationService` (plaintext rows to encrypted), `SecretResolver`.

quantum-enterprise: decrypts directly through `ManagedSecretRepo` + `SecretEncryptor`
- `quantum-filesystem-core`: `FilesystemMountStorageService`, `S3CapabilityAssessmentService` (tenant S3 credentials).
- `quantum-system-service`: `DeploymentCredentialService`, `ContainerImageCatalogService` (read),
  `GoogleSheetsOAuthService` (read and write OAuth credentials).
- `quantum-action-enablement-enterprise`: `ManagedSecretConfiguredResolver` (only checks that a secret is
  configured; metadata, not values).

b2bi (application)
- Through `SecretResolver`: `B2biSftpCredentials` (read), AS2 keys (on `feat/as2`).
- Writes through `SecretEncryptor` + `ManagedSecretRepo`: `B2biSftpCredentials`, `B2biParticipationService`
  (onboarding secrets), `MigrateSftpPasswords`.
- **Readable secrets stored in `valueEncrypted`** (bugs to fix independently of this design; see §7, phase 0):
  - `B2biInviteEmailConfigurationService` saves the tenant's Postmark server token unencrypted.
  - `B2biLocalDemoBootstrapService` saves configured secret values (demo S3 keys) unencrypted.
  - `B2biWorkflowConnectorResolver` reads `valueEncrypted` as the value (shared-storage S3 credentials),
    so it only works with unencrypted rows.
- Metadata only: `B2biParticipationFingerprintService` (secret version for change detection).

This is the case for the design: each of those callers carries its own copy of the store logic, and
three of them got it wrong.

## 4. Design

### 4.1 Layers

```
 application code
        │  SecretService  (the only API applications use)
        ▼
 ┌──────────────────────────┐        ┌──────────────────────────────┐
 │ SecretCatalog (Mongo)    │        │ SecretStore SPI              │
 │ ManagedSecret rows:      │◄──────►│  managed │ aws-sm │ gcp-sm │ │
 │ refName, type, store id, │        │  azure-kv │ vault            │
 │ version, value location  │        └───────────┬──────────────────┘
 └──────────────────────────┘                    │ (managed store only)
                                                 ▼
                                    ┌──────────────────────────────┐
                                    │ KeyProvider SPI              │
                                    │  config │ aws-kms │ gcp-kms │ │
                                    │  azure-kv                    │
                                    └──────────────────────────────┘
```

- **`SecretService`**: the application-facing API (§4.2). It owns realm checks, catalog bookkeeping,
  typed errors and audit. `SecretResolver` stays as a thin, source-compatible delegate for
  `${secret.vault://...}` resolution.
- **Catalog**: the `ManagedSecret` row stays the per-realm record of a secret for every store: refName,
  display name, type, description, data domain, `providerType` (now the store id), version, and for
  the `managed` store the encrypted value itself. Listing, "is it configured" checks
  (`ManagedSecretConfiguredResolver`) and fingerprinting keep working the same regardless of store.
- **`SecretStore`**: where values live (§4.3).
- **`KeyProvider`**: where the `managed` store's keys come from (§4.4).

### 4.2 `SecretService` (application API)

```java
public interface SecretService {
    /** The value, or empty when the realm has no such secret. Never another realm's. */
    Optional<String> resolve(String realm, String refName);

    /** Resolves every ${secret.vault://ref} in a config value; unknown references fail. */
    String resolveAll(String realm, String value);

    /**
     * Stores or replaces the value and catalog metadata. Returns the stored descriptor (refName,
     * version), never the value. Each call bumps the version.
     */
    SecretDescriptor put(String realm, SecretWrite write);

    boolean delete(String realm, String refName);

    Optional<SecretDescriptor> describe(String realm, String refName);   // metadata only
    List<SecretDescriptor> list(String realm, String secretType);         // metadata only

    /** Whether writes can succeed right now (store reachable, keys available). */
    SecretStoreStatus status(String realm);
}

public record SecretWrite(String refName, String value, String secretType,
                          String displayName, String description) {}
```

Rules
- Realm is always explicit and must be non-blank; there is no realm-less overload.
- Values are never logged, never returned from `put`, `describe` or `list`, and never placed in exception messages.
- Writes keep the caller's existing security context and data-domain stamping; the service saves the
  catalog row through `ManagedSecretRepo` exactly as callers do today.
- Errors are a typed `SecretStoreException` with a code: `NOT_FOUND`, `KEYS_UNAVAILABLE`,
  `STORE_UNAVAILABLE`, `ACCESS_DENIED`, `INVALID_REFERENCE`, `CONFLICT`.

### 4.3 `SecretStore` SPI

```java
public interface SecretStore {
    String id();                                      // "managed", "aws-secrets-manager", ...

    /** Stores the value; returns where it lives (for external stores, the provider's name/ARN + version). */
    StoredValue write(SecretLocation where, String value);

    Optional<String> read(SecretLocation where, StoredValue stored);

    void delete(SecretLocation where, StoredValue stored);

    SecretStoreStatus status();
}

public record SecretLocation(String realm, String refName) {}
```

- **`managed`** (default, in `quantum-secrets`): the value is the encrypted payload on the catalog row
  (§4.4). This is today's behaviour behind the new interface.
- **External stores** (optional modules): the value lives in the provider. The catalog row records the
  provider-native name and version. Native names come from a configurable template whose default
  isolates realms, e.g. `quantum/{application}/{realm}/{refName}`. The template must contain `{realm}`
  (checked at startup) so two realms can never share a provider secret.
- Provider credentials come from the platform's ambient identity (IAM role, workload identity,
  managed identity), never from another secret in the same store.

### 4.4 `KeyProvider` SPI and envelope encryption (`managed` store)

```java
public interface KeyProvider {
    String id();                                   // "config", "aws-kms", "gcp-kms", "azure-key-vault"

    /** A fresh data key for one secret: plaintext for immediate use, plus its wrapped form to store. */
    DataKey newDataKey(KeyScope scope);

    /** Unwraps a stored data key. */
    byte[] unwrap(KeyScope scope, WrappedKey wrapped);

    boolean available();
}

public record KeyScope(String realm) {}           // lets a provider pick a key per realm (tenant)
```

- Each secret is encrypted with its **own random data key** (AES-256-GCM), and only the data key is
  wrapped by the provider's master key. The row stores the ciphertext, IV, the wrapped data key and
  the master key's id/version.
- **`config`** keeps today's `quantum.secrets.kek.v<N>` keys as the master key, so existing deployments
  need no new infrastructure. Existing rows (encrypted directly under a KEK) stay readable: no
  wrapped key means "legacy direct", decrypted the way it is today.
- **KMS providers** (`aws-kms`, `gcp-kms`, `azure-key-vault`) wrap and unwrap through the KMS, so the
  master key never exists in the application. This removes the KEK environment variable entirely.
- **Per-tenant keys**: `KeyScope.realm` lets a provider map a realm to its own key (config:
  `quantum.secrets.kms.key.<realm>=...` with a default). Revoking one tenant's key then makes only
  that tenant's secrets unreadable.
- **Master key rotation** rewraps data keys only; secret payloads are not re-encrypted.

### 4.5 Selection by configuration

```properties
quantum.secrets.store=managed                 # managed | aws-secrets-manager | gcp-secret-manager | azure-key-vault | hashicorp-vault
quantum.secrets.key-provider=config           # config | aws-kms | gcp-kms | azure-key-vault   (managed store only)
quantum.secrets.store.realm.<realm>=...       # optional per-realm override (phase 3)
```

- Implementations are CDI beans in optional modules, selected with `@LookupIfProperty`. Switching
  clouds is a dependency change plus configuration; application code does not change.
- Startup fails fast if the selected store or key provider is not on the classpath, or if its
  required configuration is missing. That error names the property and the module to add.
- Reads always dispatch on the store recorded on the catalog row, not the current setting, so changing
  `quantum.secrets.store` never orphans existing secrets. New writes go to the configured store.

### 4.6 Moving between stores and keys

- **`managed` → external store**: an explicit, per-realm admin command (extending
  `SecretsMigrationService`) that copies each secret into the target store, verifies a read-back,
  switches the catalog row, and then deletes the old payload. It is idempotent and resumable, and
  reports per secret. It never runs implicitly at startup.
- **`config` KEK → KMS**: the same command rewraps (legacy direct rows are re-encrypted once under a
  data key).
- The existing `rotate-keys` endpoint keeps its contract and calls the rotation path for the active store.

### 4.7 Plaintext

- New writes never store plaintext. `quantum.secrets.allow-plaintext-fallback` remains dev-only, as
  today, and is rejected at startup outside dev/test profiles.
- Plaintext rows detected on read (`iv` missing) fail closed outside dev (as `ManagedSecretResource`
  already does), and `SecretsMigrationService` keeps converting them.

### 4.8 Audit

Every `put`, `delete`, `resolve` and store migration emits an audit event: actor, realm, refName, store
id, action, outcome and correlation id. It never includes a value.

## 5. Packaging

| Artifact | Contents | Depends on |
| --- | --- | --- |
| `quantum-secrets` | `SecretService`, `SecretStore`/`KeyProvider` SPIs, `managed` store, `config` key provider, catalog, REST resource | core only (no cloud SDKs) |
| `quantum-secrets-aws` | `aws-secrets-manager` store, `aws-kms` key provider | AWS SDK v2 |
| `quantum-secrets-gcp` | `gcp-secret-manager` store, `gcp-kms` key provider | Google Cloud client libraries |
| `quantum-secrets-azure` | `azure-key-vault` store and key provider | Azure SDK |
| `quantum-secrets-vault` | `hashicorp-vault` store (KV v2) and transit key provider | Vault HTTP API |

Cloud modules are optional, and an application includes only the one for its cloud.

## 6. Constraints and decisions

1. **Open-source boundary**: everything above is generic and lives in quantum-framework. Nothing
   Helixor-specific belongs here.
2. **Realm isolation is enforced in the service, not just by convention**: `SecretLocation` always
   carries the realm, the naming template must include it, and `resolve` never falls back to another realm.
3. **One write path**: an architecture test (ArchUnit) fails the build if code outside `quantum-secrets`
   calls `SecretEncryptor` or writes `ManagedSecret.valueEncrypted`.
4. **Fail closed**: every SPI method throws a typed exception rather than returning a default. There
   is no "store unavailable, use cache" path unless a store is explicitly configured with a read cache (6.6).
5. **Backward compatibility**: existing rows, references and the REST contract keep working unchanged
   through every phase.
6. **No polling**: external stores are read on demand. An optional bounded in-memory read cache per
   `(realm, ref, version)` with a TTL may be enabled per store; it is invalidated on `put`/`delete`
   through this service and never refreshed in the background.

## 7. Delivery plan

| Phase | Scope | Behaviour change |
| --- | --- | --- |
| **0** (b2bi, independent) | Fix the three unencrypted-secret paths in §3: write through the existing encrypt path, read through `SecretResolver`, and migrate the existing rows | Fixes a live bug |
| **1** | `SecretService`, the SPIs, the `managed` store and the `config` key provider in `quantum-secrets`; `SecretResolver` and `ManagedSecretResource` delegate to it | None |
| **2** | Move callers onto `SecretService` (6 in quantum-enterprise, the b2bi services); add the ArchUnit rule | None |
| **3** | Envelope encryption (data keys); `quantum-secrets-aws` (`aws-kms`, `aws-secrets-manager`); store/key migration command; per-realm override | Opt-in by config |
| **4** | GCP, Azure and Vault modules | Opt-in by config |

After phase 3, b2bi on AWS sets `quantum.secrets.key-provider=aws-kms` (or `store=aws-secrets-manager`), runs
the migration command per realm, and drops the `b2bi/<env>/secrets-kek` secret.

## 8. Testing

- A contract test suite that every `SecretStore` and `KeyProvider` implementation must pass:
  round-trip, versioning, delete, realm isolation (a write in realm A is unreadable from realm B),
  typed errors, and no value in logs or exceptions.
- Cloud modules run that suite against local emulators where they exist (LocalStack for AWS, the
  Secret Manager emulator for GCP, Vault dev server); KMS paths are covered with the providers' test
  doubles.
- Migration command tests: interrupted and resumed runs, read-back verification, rollback on mismatch.
- The ArchUnit rule from §6.3.

## 9. Open questions

1. **Cloud module home**: quantum-framework (open source, as above) or quantum-enterprise? The SPI and
   `managed` store must be in quantum-framework either way.
2. **Per-realm store overrides**: needed in phase 3, or is one store per deployment enough to start?
3. **External store as source of truth**: should the catalog row be required for external secrets, or
   should `resolve` also find provider secrets created outside the application (e.g. by Terraform)
   under the naming template?
4. **Read cache**: off by default (always read through), or on with a short TTL for external stores
   (cost and latency versus freshness)?
5. **Rotation of values** (not keys): in scope here (rotation hooks per secret type), or left to callers?
