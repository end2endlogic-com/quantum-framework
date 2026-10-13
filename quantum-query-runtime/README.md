# Quantum Query Runtime

Reusable BIAPI predicates over JSON values, with no repository or HTTP endpoint
ownership. Persistence adapters depend on this module; the runtime does not
depend on `quantum-morphia-repos` or `quantum-framework`.

The existing `com.e2eq.framework.query.runtime` APIs and predicate behavior are
preserved. The Morphia write-permission guard consumes this module so minimal
resource servers can evaluate write rules without the framework aggregator.

This extraction is not a new data-source adapter. Existing BSON value support
and optional ontology-edge resolution through the Mongo ontology integration
remain unchanged. Other data sources can reuse ordinary in-memory predicates;
the existing edge-query path is not a storage-neutral ontology implementation.
