# Concord — Collaborative Document Editor

A real-time, Google-Docs-style plain-text collaborative editor built around **Operational Transformation (OT)**, as a learning project to understand OT, distributed-systems invariants, and service decomposition deeply. Five independently deployable services, no snapshotting (the op log is append-only forever, by design), and every cross-service call is a real network call (gRPC, REST, WebSocket, or JDBC) — nothing is an in-process shortcut.

> **Full documentation** (architecture, OT/CRDT deep-dives, sequence diagrams, scaling story, bug postmortems, interview Q&A) lives in [`frontend-server/src/main/resources/static/docs/architecture.md`](frontend-server/src/main/resources/static/docs/architecture.md) and is also rendered live in the app itself at `/docs.html` once running. The index below links straight into it.

---

## Table of Contents

- [Quick Start (Docker Compose)](#quick-start-docker-compose)
- [Running Without Docker](#running-without-docker)
- [Project Structure](#project-structure)
- [Ports Reference](#ports-reference)
- [Environment Variables](#environment-variables)
- [Testing](#testing)
- [Documentation Index](#documentation-index)
- [Known Limitations](#known-limitations)

---

## Quick Start (Docker Compose)

The whole stack — Postgres, `document-service`, `connection-tier`, `document-metadata-service`, `frontend-server` — builds and runs as five containers on a shared Docker network.

```bash
git clone https://github.com/AK9175/concord.git
cd concord
docker compose up -d --build
```

Then open **http://localhost:8082**. Everything (durable storage, all five services) is up at that point — no extra setup.

```bash
docker compose ps                              # confirm all 5 are healthy/running
docker compose logs -f connection-tier          # tail any one service's logs
docker compose down                             # stop everything (data volume persists)
docker compose down -v                          # stop AND wipe the Postgres volume
```

## Running Without Docker

Useful for active development on one service at a time.

**1. Start Postgres only:**

```bash
docker compose up -d postgres
```

**2. Build everything and package each service as a self-contained fat jar:**

```bash
mvn clean package
```

**3. Run each service** (each blocks its terminal — use separate tabs, or background with `&`):

```bash
java -jar document-service/target/document-service-0.1.0-SNAPSHOT.jar
java -jar connection-tier/target/connection-tier-0.1.0-SNAPSHOT.jar
java -jar document-metadata-service/target/document-metadata-service-0.1.0-SNAPSHOT.jar
java -Dfrontend.dir=frontend-server/src/main/resources/static \
     -jar frontend-server/target/frontend-server-0.1.0-SNAPSHOT.jar
```

All four default to `localhost` for inter-service addresses (matching Postgres on `5432`, `document-service` on `9090`, `connection-tier`'s admin port on `8091`), so no environment variables are required for an all-local run. See [Environment Variables](#environment-variables) to point any service at a different host (e.g. running services on separate machines).

## Project Structure

| Module | Type | Purpose |
|---|---|---|
| `ot-core` | Library | `Operation`/`transform()`/`apply()` — the OT engine itself, zero dependencies |
| `document-service-proto` | Library | `.proto` schema + generated gRPC stubs shared between `document-service` and its callers |
| `connection-tier-proto` | Library | `.proto` schema + generated gRPC stubs for connection-tier's admin (eviction) RPC |
| `document-service` | Service | OT sequencing + durable Postgres-backed op log; gRPC server |
| `connection-tier` | Service | WebSocket termination, presence/cursors; gRPC client to `document-service`; small admin gRPC server |
| `document-metadata-service` | Service | Document catalog + per-document roster; REST; orchestrates document deletion |
| `frontend-server` | Service | Static HTML/CSS/JS, zero build step (Tailwind + marked + mermaid all via CDN) |

Every service module has its own `Dockerfile`; `docker-compose.yml` at the repo root wires all of them together.

## Ports Reference

| Port | Service | Protocol |
|---|---|---|
| 8082 | `frontend-server` | HTTP (static files) |
| 8083 | `document-metadata-service` | HTTP (REST) |
| 8081 | `connection-tier` | WebSocket |
| 8091 | `connection-tier` | gRPC (admin/eviction only) |
| 9090 | `document-service` | gRPC |
| 5432 | Postgres | JDBC |

## Environment Variables

| Variable | Read by | Default |
|---|---|---|
| `CONCORD_DB_URL` | `document-service`, `document-metadata-service` | `jdbc:postgresql://localhost:5432/concord` |
| `CONCORD_DB_USER` | `document-service`, `document-metadata-service` | `concord` |
| `CONCORD_DB_PASSWORD` | `document-service`, `document-metadata-service` | `concord` |
| `DOCUMENT_SERVICE_HOST` | `connection-tier`, `document-metadata-service` | `localhost` |
| `DOCUMENT_SERVICE_PORT` | `connection-tier`, `document-metadata-service` | `9090` |
| `CONNECTION_TIER_ADMIN_HOST` | `document-metadata-service` | `localhost` |
| `CONNECTION_TIER_ADMIN_PORT` | `document-metadata-service` | `8091` |

`docker-compose.yml` sets all of these to the right Docker-internal-DNS hostnames already — you only need to touch them for a non-Docker, multi-machine deployment.

## Testing

```bash
docker compose up -d postgres   # most test suites need a real Postgres reachable at localhost:5432
mvn clean test
```

786+ tests across all modules, including: the OT convergence property test (300+ randomized scenarios), real two-process gRPC integration tests, a deliberate process-restart durability test, and a live container-to-container verification of document deletion (eviction + content/metadata/roster wipe).

## Documentation Index

The full doc ([`architecture.md`](frontend-server/src/main/resources/static/docs/architecture.md), also rendered at `/docs.html` once the app is running) is organized as:

| Part | Covers |
|---|---|
| [1 — Executive Summary](frontend-server/src/main/resources/static/docs/architecture.md#part-1) | What this project is and why |
| [2 — Architecture Diagrams](frontend-server/src/main/resources/static/docs/architecture.md#part-2) | Current topology, clean-architecture layering, target scaled topology |
| [3 — Component-by-Component Breakdown](frontend-server/src/main/resources/static/docs/architecture.md#part-3) | What each module owns, why it's separate, tradeoffs of every tech choice |
| [4 — Operational Transformation Deep Dive](frontend-server/src/main/resources/static/docs/architecture.md#part-4) | The Operation model, `transform()`'s 4 cases, TP1 vs TP2, the insert-survives-delete split |
| [5 — CRDTs Deep Dive](frontend-server/src/main/resources/static/docs/architecture.md#part-5) | Stable identifiers, tombstones, state- vs operation-based CRDTs, why OT was chosen over CRDTs here |
| [6 — Java Concurrency Model](frontend-server/src/main/resources/static/docs/architecture.md#part-6) | `CompletableFuture`, per-document executors as a lock substitute, the atomicity-relocation problem |
| [7 — Protocols](frontend-server/src/main/resources/static/docs/architecture.md#part-7) | WebSocket message types, REST endpoints, gRPC contracts |
| [8 — Durability (Phase 5)](frontend-server/src/main/resources/static/docs/architecture.md#part-8) | Schema, ack-after-durable-write, cold-start rebuild |
| [9 — The gRPC Split (Phase 6)](frontend-server/src/main/resources/static/docs/architecture.md#part-9) | Why gRPC, and the live two-process verification |
| [10 — Document Deletion](frontend-server/src/main/resources/static/docs/architecture.md#part-10) | Cross-service delete orchestration and ordering |
| [11 — Deployment](frontend-server/src/main/resources/static/docs/architecture.md#part-11) | Docker Compose topology |
| [12 — Bugs Found During Development](frontend-server/src/main/resources/static/docs/architecture.md#part-12) | 4 real postmortems, root cause and fix for each |
| [13 — Scaling to Millions of Users/Documents](frontend-server/src/main/resources/static/docs/architecture.md#part-13) | Consistent-hash sharding, pub/sub fanout, Postgres partitioning |
| [14 — Known Limitations](frontend-server/src/main/resources/static/docs/architecture.md#part-14) | Honest gaps in the current, single-instance design |
| [15 — Quick-Recap Q&A](frontend-server/src/main/resources/static/docs/architecture.md#part-15) | Interview-prep-style questions and answers about this project |

## Known Limitations

No authentication, no rate limiting, single Postgres instance, no horizontal scale-out (designed for in Part 13, not built), unbounded op log by design. Full list with context in [Part 14](frontend-server/src/main/resources/static/docs/architecture.md#part-14).
