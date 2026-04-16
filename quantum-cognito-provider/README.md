# Quantum Cognito Provider

This module integrates the Quantum framework with AWS Cognito using Quarkus. It provides authentication and user management features and includes tests that exercise the Cognito integration.

## Canonical identity flow (new)

The framework now standardizes identity assembly using a claims-first approach:

- Providers implement `com.e2eq.framework.model.auth.ClaimsAuthProvider` and expose `validateTokenToClaims(String token)`.
- `validateTokenToClaims` parses the IdP token and returns `ProviderClaims` with:
  - `subject`: the IdP subject (`sub`)
  - `tokenRoles`: roles/groups derived from Cognito claims (`cognito:groups` by default) and, optionally, `scope`
  - `attributes`: useful pass-through claims such as `iss`, `cognito:username`, `preferred_username`, `email`, etc.
- The framework’s `IdentityAssembler` then builds the `SecurityIdentity` for the request:
  - principal is the canonical `userId` (resolved from the database)
  - roles are merged from token + credential + user-group memberships

This provider still keeps the legacy method `validateAccessToken(String token)` for backward compatibility, but the framework prefers `validateTokenToClaims`.

### Configurable role extraction

- `aws.cognito.roles.claim-name` (default: `cognito:groups`) — claim to read roles from
- `aws.cognito.roles.union-scope` (default: `true`) — when true, token `scope` entries are also added as roles

The provider logs warnings when expected claims are missing and continues with partial data when safe.

## Required environment variables

The following environment variables configure the Cognito and MongoDB settings used by the application and tests. When running the tests with real AWS access, these values must point to your Cognito user pool and other resources.

- `APPLICATION_CLIENT_ID` – Cognito application client ID.
- `USER_POOL_ID` – Cognito user pool ID.
- `AWS_REGION` – AWS region where the user pool resides.
- `QUARKUS_HTTP_CORS_ORIGINS` – Allowed CORS origins for the HTTP server.
- `JWT_SECRET` – Secret used to sign JWTs during tests.
- `MONGODB_CONNECTION_STRING` – Mongo connection string (defaults to a local instance).
- `MONGODB_DEFAULT_SCHEMA` – Default MongoDB schema name.

Other MongoDB helper variables used in the connection string are `MONGODB_USERNAME`, `MONGODB_PASSWORD`, `MONGODB_HOST` and `MONGODB_DATABASE`.

## .env file

Create a `.env` file in the project root and populate it with the variables above. Example:

```dotenv
APPLICATION_CLIENT_ID=app-client-id
USER_POOL_ID=us-east-1_example
AWS_REGION=us-east-1
QUARKUS_HTTP_CORS_ORIGINS=http://localhost:3000
JWT_SECRET=change-me
MONGODB_CONNECTION_STRING=mongodb://localhost:27017/?retryWrites=false
MONGODB_DEFAULT_SCHEMA=system-com
AUTH_PROVIDER=cognito
```

Load the file before running Maven so that the variables are available:

```bash
source .env
mvn test
```

## Running tests without Cognito

If you do not want the tests to connect to AWS, either:

- Set `AWS_COGNITO_ENABLED=false`, or
- Set `AUTH_PROVIDER` to any value other than `cognito`.

```bash
AWS_COGNITO_ENABLED=false mvn test
# or
AUTH_PROVIDER=disabled mvn test
```

## Notes on test execution

When `APPLICATION_CLIENT_ID`, `USER_POOL_ID` and `AWS_REGION` are supplied (either directly or through `.env`), the tests connect to the specified Cognito user pool to create users and validate tokens.

This project uses Quarkus with AWS Cognito. Configuration is supplied via environment variables.

1. Copy `.env.example` to `.env`.
2. Edit `.env` and provide real values for your environment.

The provided `.env.example` includes common variables such as `QUARKUS_HTTP_CORS_ORIGINS`, `APPLICATION_CLIENT_ID`, `USER_POOL_ID`, and `AWS_REGION` that must be set for the application to run.

## Multi‑Tenancy

The framework implements multi‑tenancy at two layers:
- Database selection per tenant (coarse isolation): incoming requests are routed to a tenant‑specific MongoDB database based on a resolved `realmId`.
- Data segmentation within a database (fine‑grained isolation): every document carries a `DataDomain` tag, and a policy engine auto‑generates query and mutation filters to scope access per tenant and per use case.

This combination allows you to isolate tenants by database while still supporting more granular segmentation within the same database if needed.

### Tenancy Resolution Flow
1) Identify domain context from credentials
- The system begins with the `CredentialUserIdPassword` collection. Each credential includes or references a domain context (e.g., login domain, org key, or a realm hint embedded in the credential record).

2) Resolve `realmId`
- Using the domain context, the framework determines the canonical `realmId` for the request. The `realmId` is the authoritative tenant identity used downstream.

3) Select MongoDB database
- The `realmId` is mapped to a specific MongoDB database name. The server is configured to connect to a MongoDB cluster; for each request, it selects the database associated with the resolved `realmId`. This provides hard separation of tenant data at the database level.

4) Tag documents with `DataDomain`
- Every domain model document includes a `DataDomain` field. `DataDomain` represents the logical data partition within a realm (e.g., a division, sub‑tenant, environment, or functional segment). This enables further segmentation beyond database boundaries.

5) Auto‑generated filters from rules/policies
- The framework maintains a set of rules/policies that translate into auto‑generated query filters. These filters are applied automatically to repository/service operations to ensure only the permitted `DataDomain` slices are visible/accessible for the current principal and context.

6) Write constraints (pre/post conditions)
- Policies also include preconditions (checked before reads/writes) and postconditions (validated after the operation), enabling scenarios such as:
  - Ownership enforcement: “You can only update a credential if you are its owner.”
  - Identity matching: “You can only update a credential if its `userId` equals the currently authenticated user’s `userId`.”
  - Cross‑domain prohibitions: “You cannot read documents whose `DataDomain` differs from your allowed set.”

### End‑to‑End Request Walkthrough
- Step A: Authenticate
  - A user authenticates; the system locates their record in `CredentialUserIdPassword` and extracts the domain context.
- Step B: Realm resolution
  - The domain context is mapped to a `realmId`.
- Step C: Database binding
  - The request context is bound to the MongoDB database derived from `realmId`.
- Step D: Policy scoping
  - The framework loads the applicable policies for the principal and context. It computes an effective `DataDomain` set and generates filters.
- Step E: Operation execution
  - Read operations automatically include the policy filters.
  - Write operations validate preconditions (ownership, identity match, domain allowance). On success, the document’s `DataDomain` is set/validated. Postconditions can verify final state.

### `DataDomain` Policies
- Purpose: Decide which `DataDomain` to attach to new documents and which domains are visible/modifiable for a given principal and operation.
- Status: Intended for automatic assignment of `DataDomain` during create operations. This part of the implementation is currently incomplete, so manual assignment or interim logic may be required in create paths.

Typical policy capabilities include:
- Visibility rules: derive a set of allowed `DataDomain`s for read/aggregate queries.
- Mutation rules: restrict updates/deletes to documents owned by the user or documents in allowed domains.
- Preconditions: inputs must reference a `DataDomain` within the caller’s permitted set; caller must be the owner when policy requires.
- Postconditions: written document still resides in allowed domain; invariants preserved.

Example policy scenarios (illustrative):
- Ownership update rule: a Credential can be updated only when `doc.ownerId == principal.userId`.
- Same‑user constraint: a Credential can be updated only when `doc.userId == principal.userId`.
- Domain scoping: a user with role “Support” may read `DataDomain` in `{customerA, customerB}`, but can write only to `{customerA}`.

### Behavior by Operation Type
- Read (find/findOne/aggregate): auto‑generated filters restrict results to allowed `DataDomain`s and the current realm’s database.
- Create: the framework aims to choose/validate `DataDomain` from policy. Until the implementation is complete, ensure you explicitly set `DataDomain` according to your rules.
- Update/Delete: preconditions enforce ownership/identity/domain constraints. Operations are rejected if policy checks fail.

### Configuration and Mapping
- Realm mapping: domain context (from `CredentialUserIdPassword`) -> `realmId` -> Mongo database name.
- Cluster connection: the server connects to the configured MongoDB cluster; per‑request it selects the database that corresponds to `realmId`.

### Indexing and Performance Recommendations
- Always index the `DataDomain` field, ideally with compound indexes for common query patterns, e.g.: `{ DataDomain: 1, type: 1, updatedAt: -1 }`.
- If you perform heavy aggregations, ensure all stages respect the auto‑generated filters and that early match stages include `DataDomain`.
- For write‑heavy collections, consider additional indexes supporting ownership checks (`ownerId`, `userId`) used in preconditions.

### Security Considerations
- Deny‑by‑default: When policies are missing or ambiguous, prefer denying access rather than allowing broad queries.
- Context binding: Ensure the resolved `realmId` and allowed `DataDomain` set are immutable for the duration of a request.
- Cross‑tenant isolation: Never fall back to a shared default database once a `realmId` is resolved; errors should be explicit.
- Auditing: Log `realmId`, effective `DataDomain` set, and key policy decisions for traceability.

### Current Limitations and Roadmap
- `DataDomain` assignment policy is incomplete:
  - Today, code paths that create documents may need explicit `DataDomain` assignment. The intent is to centralize this in the policy layer so that creates consistently derive `DataDomain` from user/context/rules.
- Future enhancements:
  - Complete automatic `DataDomain` selection on create.
  - Add policy simulation tools (explain why access is allowed/denied).
  - Provide a declarative policy DSL and validation tests.

### Summary
- Resolve domain context from `CredentialUserIdPassword` -> derive `realmId`.
- Map `realmId` to a MongoDB database and bind the request to it.
- Use `DataDomain` on every document plus policy‑driven filters to segment and secure data within the database.
- Enforce pre/post conditions (ownership, identity, domain constraints) for safe reads and writes.
- Note: Automatic `DataDomain` assignment on create is intended but currently incomplete; set it explicitly until policy support is finalized.
