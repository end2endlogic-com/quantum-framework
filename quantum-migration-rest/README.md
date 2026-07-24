# Quantum Migration REST

`quantum-migration-rest` is the opt-in system control-plane adapter for
database migration, change-set execution, and index administration. It owns
the existing `/system/migration` endpoint family.

Only the system-management service should select this artifact. Application
backends and SDK-only consumers should omit it so they do not expose database
administration endpoints or acquire the server adapter.

The Java package and HTTP paths are unchanged from their former location in
`quantum-framework`.
