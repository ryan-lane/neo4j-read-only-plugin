# neo4j-read-only-plugin

[![CI](https://github.com/ryan-lane/neo4j-read-only-plugin/actions/workflows/ci.yml/badge.svg)](https://github.com/ryan-lane/neo4j-read-only-plugin/actions/workflows/ci.yml)
[![GitHub Packages](https://img.shields.io/badge/GitHub%20Packages-neo4j--read--only--plugin-blue?logo=github)](https://github.com/ryan-lane/neo4j-read-only-plugin/packages)

A Neo4j 5.x server plugin that enforces a **username-based write policy** using the [Transaction Event API](https://neo4j.com/docs/java-reference/current/extending-neo4j/transaction-event-api/).

- Users whose name ends with `_rw` (e.g. `alice_rw`, `svc_rw`) **may** commit write transactions.
- Every other user — including the built-in `neo4j` admin — is **blocked** from writing. Their transactions are rolled back and a clear error is returned to the client.
- All write attempts (allowed or blocked) are **logged** with a per-category change summary.
- Read-only queries (`MATCH`, `RETURN`, `CALL db.*`, …) always pass through for every user.

---

## Contents

- [How it works](#how-it-works)
- [Requirements](#requirements)
- [Installation](#installation)
- [User management](#user-management)
- [Usage examples](#usage-examples)
- [Docker quick-start](#docker-quick-start)
- [Running tests](#running-tests)
- [Development](#development)
- [GitHub Actions](#github-actions)
- [Architecture](#architecture)
- [Security notes](#security-notes)

---

## How it works

Neo4j's [Transaction Event API](https://neo4j.com/docs/java-reference/current/extending-neo4j/transaction-event-api/) lets a plugin register a `TransactionEventListener` that is invoked at transaction lifecycle points. This plugin hooks into `beforeCommit`:

```
client → [Cypher query] → Neo4j executes → beforeCommit fires
                                                  │
                              ┌───────────────────┴───────────────────┐
                              │ TransactionData.hasWrites() == false?  │
                              │   → pass through (reads are free)      │
                              └───────────────────┬───────────────────┘
                                                  │ writes detected
                              ┌───────────────────┴───────────────────┐
                              │ TransactionData.username() ends _rw?   │
                              │   yes → log changes, allow commit      │
                              │   no  → throw WriteNotAllowedException │
                              │         (Neo4j rolls back the tx)      │
                              └────────────────────────────────────────┘
```

`TransactionData` exposes the full in-memory delta of the transaction:

| Category | Tracked |
|---|---|
| `createdNodes()` | New nodes |
| `deletedNodes()` | Deleted nodes |
| `assignedNodeProperties()` | Property sets on nodes |
| `removedNodeProperties()` | Property removals from nodes |
| `createdRelationships()` | New relationships |
| `deletedRelationships()` | Deleted relationships |
| `assignedRelationshipProperties()` | Property sets on relationships |
| `removedRelationshipProperties()` | Property removals from relationships |

Because the check happens at the **transaction boundary** (not query parse time), no amount of Cypher query manipulation can bypass the policy. Injection patterns like `WITH 1337 AS dummy MATCH (n) DETACH DELETE n`, `CALL {}` subquery writes, `MERGE`, `UNWIND … CREATE`, etc., are all subject to the same `TransactionData` inspection.

Dynamic databases created after Neo4j starts are covered automatically via `DatabaseEventListener.databaseStart()`.

---

## Requirements

| Requirement | Version |
|---|---|
| Neo4j | 5.x (Community or Enterprise) |
| Java | 17+ |
| Maven | 3.9+ (build only) |
| Docker | 24+ (optional, for the quick-start and integration tests) |

---

## Installation

### 1. Build the plugin JAR

```bash
./mvnw package -DskipTests
```

This produces two artifacts in `target/`:

| File | Purpose |
|---|---|
| `neo4j-read-only-plugin-1.0.0-SNAPSHOT.jar` | Thin JAR (no bundled dependencies) |
| `neo4j-read-only-plugin-1.0.0-SNAPSHOT-plugin.jar` | **Shaded JAR – deploy this one** |

> The shaded `-plugin.jar` bundles any non-Neo4j dependencies. Neo4j's own classes are excluded because they are already on the server classpath.

### 2. Copy the JAR to the Neo4j plugins directory

```bash
cp target/neo4j-read-only-plugin-*-plugin.jar $NEO4J_HOME/plugins/
```

### 3. Restart Neo4j

```bash
$NEO4J_HOME/bin/neo4j restart
```

Confirm the plugin loaded by checking the Neo4j log:

```
INFO  ReadOnlyPlugin: registered transaction event listener on database 'neo4j'
INFO  ReadOnlyPlugin: registered database-event listener for dynamic database tracking
```

### GitHub Packages

Published JARs are available from [GitHub Packages](https://github.com/ryan-lane/neo4j-read-only-plugin/packages).  To consume them as a Maven dependency, add the repository to your `pom.xml`:

```xml
<repositories>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/ryan-lane/neo4j-read-only-plugin</url>
  </repository>
</repositories>
```

---

## User management

Create write-capable users whose names end with `_rw`. Any naming convention before the suffix is fine.

```cypher
-- Connect to the system database as admin
-- (cypher-shell -u neo4j -p <password> -d system)

CREATE USER app_rw     SET PASSWORD 'strongpassword' CHANGE NOT REQUIRED;
CREATE USER readonly   SET PASSWORD 'strongpassword' CHANGE NOT REQUIRED;
CREATE USER reporting  SET PASSWORD 'strongpassword' CHANGE NOT REQUIRED;
```

| Username | Can write? | Reason |
|---|---|---|
| `app_rw` | ✅ yes | ends with `_rw` |
| `svc_pipeline_rw` | ✅ yes | ends with `_rw` |
| `readonly` | ❌ no | no `_rw` suffix |
| `reporting` | ❌ no | no `_rw` suffix |
| `svc_rw_admin` | ❌ no | `_rw` is not the suffix |
| `neo4j` | ❌ no | built-in admin, no `_rw` suffix |

---

## Usage examples

### Allowed write (alice_rw)

```bash
cypher-shell -u alice_rw -p password \
  "CREATE (n:Person {name: 'Alice'}) RETURN n.name"
```

```
name
"Alice"
```

### Blocked write (alice)

```bash
cypher-shell -u alice -p password \
  "CREATE (n:Person {name: 'Alice'}) RETURN n.name"
```

```
User 'alice' is not permitted to perform write operations.
Write access requires a username ending in '_rw'.
```

### Read always succeeds (alice)

```bash
cypher-shell -u alice -p password \
  "MATCH (n:Person) RETURN n.name LIMIT 5"
```

```
name
"Alice"
```

### Log output for a write transaction

```
INFO  Write transaction by user 'alice_rw':
        createdNodes=1, deletedNodes=0,
        createdRelationships=0, deletedRelationships=0,
        assignedNodeProperties=1, removedNodeProperties=0,
        assignedRelationshipProperties=0, removedRelationshipProperties=0
```

Detailed per-node/relationship/property information is logged at `DEBUG` level. Enable it in `$NEO4J_HOME/conf/user-logs.xml` by setting the plugin package to `DEBUG`:

```xml
<logger name="com.example.neo4j" level="DEBUG"/>
```

---

## Docker quick-start

The all-in-one image compiles the plugin and bakes it into Neo4j in a single multi-stage build:

```bash
# Build and start
docker compose --profile all-in-one up neo4j-with-plugin

# Neo4j browser → http://localhost:7474  (neo4j / changeme)
# Bolt           → bolt://localhost:7687
```

**Create test users and verify the policy:**

```bash
# Create users
docker exec <container> cypher-shell -u neo4j -p changeme -d system \
  "CREATE USER writer_rw SET PASSWORD 'pass' CHANGE NOT REQUIRED;
   CREATE USER reader    SET PASSWORD 'pass' CHANGE NOT REQUIRED;"

# ✅ Write allowed
docker exec <container> cypher-shell -u writer_rw -p pass \
  "CREATE (:Test {msg: 'hello'}) RETURN 'ok'"

# ❌ Write blocked
docker exec <container> cypher-shell -u reader -p pass \
  "CREATE (:Test {msg: 'hello'}) RETURN 'ok'"

# ✅ Read always works
docker exec <container> cypher-shell -u reader -p pass \
  "MATCH (n:Test) RETURN n.msg"
```

**Tear down:**

```bash
docker compose --profile all-in-one down -v
```

---

## Running tests

The test suite has three layers:

| Layer | Class | Runner | What it covers |
|---|---|---|---|
| Unit / harness | `ReadOnlyPluginIT` | embedded Neo4j harness | Username check logic, mock `TransactionData` |
| E2E policy | `WriteEnforcementIT` | Testcontainers / Docker Compose | Full read-write policy over Bolt, username suffix edge cases |
| Injection resilience | `CypherInjectionIT` | Testcontainers / Docker Compose | Destructive patterns, payload storage, log injection, SSRF+write |

### Docker Compose (recommended — no local JDK needed)

```bash
# Build both images and run all 94 tests
docker compose -f docker-compose.test.yml run --rm test-runner

# Test reports land on the host at ./failsafe-reports/
docker compose -f docker-compose.test.yml run \
  -v "$(pwd)/failsafe-reports:/build/target/failsafe-reports" \
  --rm test-runner

# Always clean up afterwards
docker compose -f docker-compose.test.yml down -v
```

The test runner container exits with code `0` on success and non-zero on any failure.

### Local Maven (requires Java 17 and Docker)

```bash
# Unit tests only (fast, no Docker required)
./mvnw test

# Full suite including integration tests (Docker required for Testcontainers mode)
./mvnw verify
```

> **macOS note:** `./mvnw` requires `wget` or `curl`. If `wget` is broken, install it via `brew install wget` or switch to `./mvnw` using curl via the Maven wrapper properties.

### What the injection tests verify

The `CypherInjectionIT` suite confirms that the plugin's transaction-level check cannot be bypassed by:

- **Destructive write patterns** — `DETACH DELETE`, `WITH 1337 AS dummy MATCH (n) DELETE n`, `CALL {} `subquery writes, `MERGE`, `UNWIND … CREATE`, mass `SET`, `REMOVE`
- **Comment / whitespace injection** — trailing `//`, block comments `/**/`, inline comments mid-clause, backtick-label injection
- **Numeric/boolean injection** — `WHERE n.id = '' OR 1=1 SET n.owned = true`
- **Payload storage** — injection strings (`' OR 1=1 RETURN n //`, `LOAD CSV` exfiltration payloads, `DROP DATABASE` strings) stored as property values behave as ordinary writes: allowed for `_rw`, blocked for others
- **Log injection** — property values containing `\n`, `\r\n`, JNDI patterns (`${jndi:ldap://…}`), Log4Shell variants, ANSI escapes, and null bytes do not crash or corrupt the plugin logger
- **SSRF + write** — `LOAD CSV FROM '…' AS row CREATE (…)`: either the network error or the plugin blocks the write; either way no data is written
- **Parameterized queries** — using driver-level parameters (the correct Cypher injection defence) does not bypass the write check

---

## Development

### Prerequisites

- Java 17
- Maven 3.9+ (or use the included `./mvnw` wrapper)
- Docker 24+ (for integration tests)

### Build

```bash
# Compile and package (skip tests for speed)
./mvnw package -DskipTests

# Full build with all tests via Docker Compose
./mvnw package && \
  docker compose -f docker-compose.test.yml run --rm test-runner
```

### Project structure

```
neo4j-read-only-plugin/
├── Dockerfile                   # Multi-stage: build plugin → bake into Neo4j image
├── Dockerfile.testrunner        # Maven test-runner image
├── docker-compose.yml           # Production-style quick-start
├── docker-compose.test.yml      # Integration-test environment
├── pom.xml
└── src/
    ├── main/java/com/example/neo4j/
    │   ├── ReadOnlyPluginExtensionFactory.java   # @ServiceProvider entry point
    │   ├── ReadOnlyPluginExtension.java           # LifecycleAdapter + DatabaseEventListener
    │   ├── ReadOnlyTransactionEventListener.java  # TransactionEventListener – core policy
    │   └── WriteNotAllowedException.java          # Checked exception thrown on blocked write
    ├── main/resources/META-INF/services/
    │   └── org.neo4j.kernel.extension.ExtensionFactory  # Service-loader registration
    └── test/java/com/example/neo4j/
        ├── ContainerSetup.java       # Shared Neo4j connection (Testcontainers or external)
        ├── WriteEnforcementIT.java   # E2E policy tests
        ├── CypherInjectionIT.java    # Injection resilience tests
        ├── ReadOnlyPluginIT.java     # Unit / harness tests
        ├── MockTransactionData.java  # TransactionData stub for unit tests
        └── NoOpLog.java              # Silent Log for unit tests
```

### Key classes

**`ReadOnlyPluginExtensionFactory`**

The Neo4j extension entry point. Annotated with `@ServiceProvider` and extending `ExtensionFactory<Dependencies>` at `ExtensionType.GLOBAL` scope. Neo4j discovers it via the `META-INF/services/` file and injects `DatabaseManagementService` and `LogService` automatically.

**`ReadOnlyPluginExtension`**

A `LifecycleAdapter` that also implements `DatabaseEventListener`. On `start()`, it registers the transaction listener on all existing databases and subscribes to future database lifecycle events. On `databaseStart()`, it registers on newly created databases. On `databaseShutdown()`, `databaseDrop()`, and `databasePanic()`, it deregisters. The `system` database is skipped (Neo4j forbids transaction event listeners there).

**`ReadOnlyTransactionEventListener`**

Implements `TransactionEventListener<Void>`. `beforeCommit()`:

1. Calls `hasWrites(TransactionData)` — returns `true` if any of the eight write-category iterables is non-empty.
2. If writes exist, calls `logChanges()` — logs counts per category at `INFO`, per-entity detail at `DEBUG`.
3. If the username does not end with `_rw`, throws `WriteNotAllowedException`, causing Neo4j to roll back the transaction.

### Adding a new database

No action required. When Neo4j creates a new database, `ReadOnlyPluginExtension.databaseStart()` fires and the transaction listener is registered automatically.

### Changing the write-permission pattern

The suffix `_rw` is defined as a constant in `ReadOnlyTransactionEventListener`:

```java
private static final String RW_SUFFIX = "_rw";
```

Change it there. If you need a more complex rule (e.g., role-based, regex), modify the condition in `beforeCommit()`:

```java
if (!username.endsWith(RW_SUFFIX)) {
    throw new WriteNotAllowedException(…);
}
```

---

## GitHub Actions

### CI (`.github/workflows/ci.yml`)

Triggers on every pull request targeting `main`.

1. Builds `neo4j-test` and `test-runner` Docker images using BuildKit with GitHub Actions cache (`type=gha`) to avoid re-downloading Maven dependencies and the Neo4j base layer on unchanged code.
2. Runs all 94 tests via `docker compose -f docker-compose.test.yml run`.
3. Publishes JUnit XML results as inline PR annotations via `dorny/test-reporter`.
4. Uploads the raw XML as a downloadable artifact.
5. Always tears down containers and volumes.

Newer pushes to the same PR branch automatically cancel the previous run (`concurrency: cancel-in-progress: true`).

### Publish (`.github/workflows/publish.yml`)

Triggers on every push to `main` (i.e., after a PR is merged).

1. Runs the full test suite (same Docker Compose steps as CI). Publishing is blocked if any test fails.
2. Sets up Java 17 and configures `~/.m2/settings.xml` for GitHub Packages authentication using `GITHUB_TOKEN`.
3. Runs `mvn deploy` which publishes both JAR variants to [GitHub Packages](https://github.com/ryan-lane/neo4j-read-only-plugin/packages).

The `GITHUB_TOKEN` secret is automatically provided by GitHub Actions. No additional secrets configuration is required.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│  Neo4j 5.x process                                              │
│                                                                  │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  Kernel extension lifecycle                              │   │
│  │                                                          │   │
│  │  ReadOnlyPluginExtensionFactory  (@ServiceProvider)      │   │
│  │    └─ creates ReadOnlyPluginExtension (LifecycleAdapter) │   │
│  │           │                                              │   │
│  │           ├─ start()  ─── registers TransactionListener  │   │
│  │           │               on all existing databases       │   │
│  │           │           ─── subscribes DatabaseEventListener│   │
│  │           │                                              │   │
│  │           ├─ databaseStart()  ─── registers on new DB    │   │
│  │           ├─ databaseShutdown() ─ unregisters from DB    │   │
│  │           └─ stop()   ─── unregisters DatabaseListener   │   │
│  └──────────────────────────┬────────────────────────────────┘  │
│                             │ per database                       │
│  ┌──────────────────────────▼────────────────────────────────┐  │
│  │  ReadOnlyTransactionEventListener                          │  │
│  │                                                            │  │
│  │  beforeCommit(TransactionData, tx, db)                     │  │
│  │    1. hasWrites(data)?        ── no  → allow               │  │
│  │    2. logChanges(data, user)  ── INFO + DEBUG              │  │
│  │    3. username.endsWith("_rw")── yes → allow               │  │
│  │                                   no  → throw              │  │
│  │                                         WriteNotAllowed    │  │
│  │                                         Exception          │  │
│  │                                         (Neo4j rolls back) │  │
│  └────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

---

## Security notes

### What this plugin does and does not do

| Provides | Does not provide |
|---|---|
| Write blocking based on username suffix | Role-based access control (use Neo4j Enterprise RBAC for that) |
| Audit log of every write transaction | Fine-grained property/label-level restrictions |
| Coverage of all write operations via `TransactionData` | Protection of the `system` database (Neo4j does not support transaction event listeners there) |
| Automatic coverage of dynamically created databases | Protection against a compromised Neo4j process itself |

### Injection resilience

The check operates at the **transaction commit boundary**, after Cypher is parsed, planned, and executed. The username is read from `TransactionData.username()`, which reflects the authenticated session identity — it is never derived from query content. This means:

- No Cypher injection in query strings can forge a different username or bypass the `_rw` check.
- Storing injection payloads as property values (e.g., `' OR 1=1 RETURN n //`) is treated as an ordinary write: allowed for `_rw` users, blocked for others.
- The plugin's `logChanges()` method logs **counts** and **entity IDs** only — it does not interpolate property values into log messages, preventing log injection via stored data.

See [`CypherInjectionIT.java`](src/test/java/com/example/neo4j/CypherInjectionIT.java) for the full injection test suite.

### Neo4j Community vs Enterprise

On **Community Edition**, all users have full database access; this plugin is the only write gate. On **Enterprise Edition**, this plugin can be layered on top of the built-in RBAC to add an extra enforcement layer or for use cases where RBAC is not sufficient.

### Production recommendations

- Use strong passwords for `_rw` accounts and rotate them regularly.
- Restrict network access to the Bolt port (7687) to trusted clients only.
- Enable Neo4j's built-in auth and TLS.
- Consider using a secrets manager to inject the `_rw` credentials at runtime.
- Review the Neo4j security documentation for your version: https://neo4j.com/docs/operations-manual/current/security/
