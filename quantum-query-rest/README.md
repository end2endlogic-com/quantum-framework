# Quantum Query REST

`quantum-query-rest` is the opt-in HTTP adapter for Quantum's generic
Morphia Query Gateway. It owns the existing `/api/query` endpoint family.

Use this artifact only in a backend that intentionally exposes generic query
operations. Endpoint-free modules and SDK-only consumers should not depend on
it.

The historical `quantum-framework` artifact depends on this module for
compatibility, so existing applications retain the same endpoint URLs.
