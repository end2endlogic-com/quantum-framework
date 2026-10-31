# Quantum Ontology Mongo

`quantum-ontology-mongo` contains the Mongo persistence, repositories, write
hooks, materialization, reindexing, drift repair, and tenant-aware ontology
services. It intentionally contains no JAX-RS resource classes and no longer
depends on `quantum-framework`.

Server deployments that expose the ontology API add
`com.end2endlogic:quantum-ontology-rest`. Applications that call the standalone
ontology control plane use the SDK generated from `quantum-ontology-service`
instead of adding either server implementation jar.

The REST API guide is in
[`../quantum-ontology-rest/README.md`](../quantum-ontology-rest/README.md).
