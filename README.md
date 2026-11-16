# WAL-Backed Key-Value Store (`com.interview.kvstore`)

A production-grade Write-Ahead Log (WAL) key-value database engine built from raw `java.nio` file I/O, hardened against torn writes and corrupted/malicious input, with atomic compaction, group commit batching, range/prefix queries, and a Spring Boot REST API.

---

## Architecture & Production Capabilities

```
+-----------------------------------------------------------------------------------+
|                                Client Applications                                |
|          (HTTP REST API / CLI REPL / Docker Container / Java Library)             |
+-----------------------------------------------------------------------------------+
                                         │
                                         ▼
+-----------------------------------------------------------------------------------+
|                        StorageEngine (Concurrency Coordinator)                    |
|  - ConcurrentSkipListMap (In-Memory O(log N) Sorted Index: Range & Prefix Scans)  |
|  - ReentrantLock (Guarantees WAL Sequence Order == Memory Application Order)      |
+-----------------------------------------------------------------------------------+
                    │                                             │
      Write Path    │                                             │  Read Path
   (PUT / DELETE)   │                                             │  (GET / SCAN / RANGE)
                    ▼                                             ▼
+---------------------------------------+          +--------------------------------+
|       WalWriter (Durability Core)     |          | ConcurrentSkipListMap (Memory) |
|  - Binary Frame Serialization         |          | - Sub-microsecond lock-free    |
|  - CRC32 Checksumming                 |          | - Natural key ordering         |
|  - Hardware fsync: force(true)        |          | - Concurrent reads & iteration |
|  - Group Commit Batch Writes          |          +--------------------------------+
+---------------------------------------+
                    │
                    ▼
+-----------------------------------------------------------------------------------+
|                                Physical Disk (.wal)                               |
|                     store.wal  <───  store.wal.compact.tmp                        |
|                                                                                   |
|  Atomic Compaction: Reclaims disk from overwrites & deletes with zero data loss.  |
|  RecoveryManager: Fast replay + torn-write repair + corruption detection.        |
+-----------------------------------------------------------------------------------+
```

### 1. Atomic Zero-Loss Log Compaction & Snapshotting
* **Problem Solved:** An append-only WAL grows indefinitely over time, slowing down restarts and exhausting disk storage.
* **Mechanism:** When manual or automatic compaction triggers (`wal.auto-compact.threshold-bytes`), `StorageEngine.compact()` creates an atomic snapshot of live keys into a temporary `.compact.tmp` file, flushes it to disk with `force(true)`, and atomically swaps it over the active WAL via `ATOMIC_MOVE`. Obsolete historic mutations and tombstones (deleted keys) are reclaimed, making recovery instantaneous.

### 2. Group Commit & Atomic Batch Writes
* **Problem Solved:** Individual `fsync` calls limit standard hard drives and SSDs to ~100–300 writes/second.
* **Mechanism:** `WalWriter.appendBatch` groups multiple operations into a single combined buffer and executes **one physical fsync** across the entire batch. This scales throughput to tens of thousands of operations/second.

### 3. Sorted In-Memory Index (Range & Prefix Queries)
* Powered by `ConcurrentSkipListMap`, the store maintains keys in lexicographical order.
* Provides $O(\log N)$ prefix lookups (`scan("user:", limit)`) and boundary range queries (`range("a", "m", limit)`).

### 4. Crash Hardening & Resilient Recovery
1. **Size Pre-Validation:** `WalReader.MAX_ENTRY_SIZE_BYTES` (16 MiB) sanity limits are checked *before* buffer allocation to prevent OutOfMemory denial-of-service from bit-flips.
2. **Torn Write vs. Corruption Segregation:** 
   * **Torn writes** (crashes mid-append where the file ends early) are automatically detected, truncated away, and the truncation itself is forced to disk with `force(true)`.
   * **Corrupted records** (bad CRC32, impossible lengths, sequence number breaks) cause recovery to fail loudly with `StorageException` rather than silently deleting data.
3. **Continuous Monotonic Sequence Numbers:** Sequence numbers must be strictly consecutive (`0, 1, 2, ...`). Any gap or duplicate is detected as corruption.
4. **Clean Decoupling of PUT vs DELETE:** `PUT key ""` (empty string) and `DELETE key` are cleanly distinguished during deserialization.
5. **Exclusive Process File Locking:** `StorageEngine` acquires an exclusive OS-level `FileLock` on `<walFile>.lock` upon initialization. This prevents two active processes (e.g. CLI and Spring Boot server) from corrupting the same WAL file simultaneously.
6. **Pre-Write Buffer Size Guard:** `WalWriter` strictly validates total entry payload bytes before memory allocation or advancing sequence numbers, preventing integer overflows or OutOfMemory spikes.
7. **Compaction Resilience & POSIX Directory Fsync:** If compaction file replacement fails, `StorageEngine` recovers by restoring an active writer on the intact original file. Following replacement, the parent directory table is fsynced to ensure rename metadata is physically flushed to disk.
8. **Deep Readiness Health Probes:** `/admin/health` actively probes `StorageEngine`'s operational status (valid lock and open write channel) instead of returning a static response.

### 5. Tamper-Evident WAL Integrity Chain (HMAC-SHA-256)
* **Problem Solved:** Traditional CRC32 checksums only defend against accidental bit-flips or truncated writes; an attacker with file access can easily alter records in a hex editor and recalculate the 4-byte CRC32.
* **Mechanism:** When `wal.integrity.enabled=true`, the store transitions to Format v2:
  * WAL begins with a 16-byte magic header (`WALKVS02` + version 2 + flags).
  * Each record appends a 32-byte cryptographic tag computed via `HMAC-SHA-256(secret, DOMAIN || prevHmacTag || canonicalRecordBytes)`.
  * The first record chains from an all-zero 32-byte **genesis tag**.
  * Any record modification, deletion from the middle, or reordering breaks the chain downstream and halts startup recovery with `StorageException` without truncating.

---

## Quickstart: Running Locally

### Prerequisites
* Java 17 or higher (`java -version`)
* Maven 3.8 or higher (`mvn -version`)

### 1. Start the HTTP Server
```bash
mvn spring-boot:run
```
The server will start on port `8080` using `./wal-data/store.wal`.

### 2. Verify Readiness
```bash
curl http://localhost:8080/admin/health
```
Expected output:
```json
{"status":"UP","walWritable":true,"keyCount":0}
```

---

## REST API Reference

The server runs on port `8080` by default.

### Key-Value CRUD
| Method | Endpoint | Description | Example Request / Response |
| :--- | :--- | :--- | :--- |
| `PUT` | `/keys/{key}` | Store or update a key | Body: `{"value": "Alice"}` |
| `GET` | `/keys/{key}` | Fetch a single key | Returns `{"key": "user:1", "value": "Alice"}` |
| `DELETE`| `/keys/{key}` | Durably delete a key | Returns `{"status": "durably deleted"}` |
| `GET` | `/keys` | Count active keys | Returns `{"count": 42}` |

### Querying: Prefix & Range Scans
* **Prefix Search:**
  ```bash
  curl "http://localhost:8080/keys?prefix=user:&limit=10"
  ```
  ```json
  {
    "prefix": "user:",
    "count": 2,
    "keys": {
      "user:101": "Alice",
      "user:102": "Bob"
    }
  }
  ```
* **Range Query:**
  ```bash
  curl "http://localhost:8080/keys?from=a&to=m&limit=50"
  ```

### Atomic Batch Writes (Group Commit)
```bash
curl -X POST http://localhost:8080/keys/batch \
  -H "Content-Type: application/json" \
  -d '{
    "puts": {
      "user:1": "Alice",
      "user:2": "Bob"
    },
    "deletes": ["user:old"]
  }'
```

### Administrative Endpoints
* **Health Check (Public):** `GET /admin/health` (no authentication required; used by Docker/Kubernetes readiness and liveness probes)
* **Telemetry & Metrics (Protected):** `GET /admin/stats` (requires `X-Admin-Token` or `Authorization: Bearer <token>`)
  ```bash
  curl -H "X-Admin-Token: secret-admin-token" http://localhost:8080/admin/stats
  ```
  ```json
  {
    "keyCount": 1500,
    "walFileSizeBytes": 45280,
    "lastCompactedAt": 1726058000,
    "recoveredEntriesCount": 1500,
    "tornWriteDetectedOnStartup": false,
    "currentSequenceNumber": 1500,
    "totalWriteOps": 3200,
    "avgWriteLatencyMicros": 412.5,
    "compactionCount": 3
  }
  ```
* **Trigger Compaction (Protected):** `POST /admin/compact` (requires `X-Admin-Token` or `Authorization: Bearer <token>`)
  ```bash
  curl -X POST -H "Authorization: Bearer secret-admin-token" http://localhost:8080/admin/compact
  ```
  ```json
  {
    "status": "compacted",
    "bytesBefore": 2548200,
    "bytesAfter": 32100,
    "bytesReclaimed": 2516100,
    "keysRetained": 1500
  }
  ```
* **Inspect Integrity Status (Protected):** `GET /admin/integrity` (requires `X-Admin-Token` or `Authorization: Bearer <token>`)
  ```bash
  curl -H "X-Admin-Token: secret-admin-token" http://localhost:8080/admin/integrity
  ```
  *When integrity mode is enabled (`wal.integrity.enabled=true`):*
  ```json
  {
    "status": "VALID",
    "integrityEnabled": true,
    "walFormatVersion": 2,
    "verifiedEntries": 1820,
    "lastSequenceNumber": 1819,
    "chainRootPrefix": "a1b2c3d4e5f6"
  }
  ```
  *When integrity mode is disabled (`wal.integrity.enabled=false`):*
  ```json
  {
    "status": "DISABLED",
    "integrityEnabled": false
  }
  ```

---

## Interactive CLI Shell

Run the interactive shell to explore the engine or test live `kill -9` crashes:

```bash
java -cp target/classes:target/test-classes com.interview.kvstore.tools.Cli wal-data/store.wal
```

Commands supported:
* `put <key> <value>`
* `get <key>`
* `delete <key>`
* `scan <prefix> [limit]`
* `range <from> <to> [limit]`
* `compact`
* `stats`
* `size`
* `exit`

---

## Live Crash & Recovery Demonstration

The repository includes `SlowWriteDemo` to demonstrate genuine mid-write crash recovery:

1. **Compile the classes:**
   ```bash
   mvn compile
   ```

2. **Start a slow write:**
   ```bash
   java -cp target/classes com.interview.kvstore.tools.SlowWriteDemo wal-data/demo.wal 10
   ```
   This writes 2 complete entries, then writes only the first half of a 3rd entry (creating a genuine torn write on disk), prints its PID, and sleeps for 10 seconds.

3. **Simulate a crash:**
   From a second terminal before the timer expires:
   ```bash
   kill -9 <PID>
   ```

4. **Verify recovery:**
   Open the WAL using the interactive CLI:
   ```bash
   java -cp target/classes:target/test-classes com.interview.kvstore.tools.Cli wal-data/demo.wal
   ```
   Output:
   ```text
   StorageEngine initialized with WAL wal-data/demo.wal. Recovered 2 entries (torn write detected: true)
   ```
   * `goodkey1` and `goodkey2` are fully intact.
   * The torn 3rd entry was detected via CRC32/size validation and cleanly discarded.

---

## Running with Docker / Docker Compose

### Using Docker Compose
```bash
docker compose up -d
```
This mounts a persistent Docker volume at `/data/wal` so data survives container restarts.

### Configuration via Environment Variables
| Variable | Default | Purpose |
| :--- | :--- | :--- |
| `SERVER_PORT` | `8080` | HTTP listening port |
| `WAL_FILE` | `/data/wal/store.wal` | Path to the active `.wal` file |
| `WAL_AUTO_COMPACT_THRESHOLD_BYTES` | `10485760` (10MB) | File size threshold for auto-compaction |
| `KVSTORE_ADMIN_TOKEN` | `secret-admin-token` | Secret key required for admin endpoints |
| `WAL_INTEGRITY_ENABLED` | `false` | Enable HMAC-SHA-256 tamper-evident integrity chain |
| `WAL_INTEGRITY_SECRET` | *(empty)* | Shared secret key for cryptographic tag calculation |

---

## Threat Model & Migration Policy

### Cryptographic Guarantees (What HMAC Chaining Detects)
When `wal.integrity.enabled=true`, the engine cryptographically links every record to its predecessor via HMAC-SHA-256 tags verified with constant-time equality checks (`MessageDigest.isEqual`):
* **Record Tampering:** Altering any byte of a payload or header produces an invalid HMAC tag and is rejected during startup recovery without truncating.
* **Deletion from Middle:** Deleting any intermediate record breaks the chain for all subsequent records because the expected predecessor tag no longer matches.
* **Reordering / Splicing:** Transposing records changes their input tags and breaks chain validation.

### Honest Security Limitations
* **Rollback & Suffix Truncation:** A hash chain alone cannot prevent an attacker with raw disk access from replacing the entire file with an older, previously valid snapshot of the same file, or truncating valid entries off the very end of the file, unless an expected head/root checkpoint is published and verified outside the WAL (e.g. in external durable storage or a ledger consensus service).
* **Compaction Audit Semantics:** Compaction intentionally snapshots only the current active live keys into a clean new chain beginning at the genesis tag. It is designed for disk space reclamation, not for long-term immutable archival history.

### Legacy WAL Migration Behavior
If `wal.integrity.enabled=true` is enabled on an existing legacy (Format v1) WAL:
* The store **deliberately refuses to start** with a clear `StorageException`.
* **Why this is safer:** Automatically signing existing historical entries on startup would create a false sense of security — any tampering that occurred prior to enabling integrity mode would be retroactively certified as authentic. Users must start with a clean WAL or perform an explicit verified migration.

---

## Test Suite

All 50 comprehensive unit and integration tests pass with zero warnings:

```bash
mvn test
```

* `WalIntegrityTest` (9 tests: cryptographic HMAC chain verification, byte tampering detection, record deletion rejection, reordering detection, torn write recovery, compaction chain preservation, missing secret validation, secret non-disclosure)
* `KeyValueControllerIntegrityHttpIntegrationTest` (Spring MVC integration test verifying `/admin/integrity` endpoint under active integrity mode)
* `KeyValueControllerHttpIntegrationTest` (Spring MVC HTTP integration tests: routing, param binding, input validation, 400/401/404 handling)
* `StorageEngineHardeningTest` (Exclusive file locking, dual-process conflict detection, dynamic health check verification)
* `StorageEngineTest` (CRUD, persistence across reboots)
* `CompactionTest` (Space reclamation, atomic file replacement, auto-compaction)
* `BatchAndScanTest` (Group commit batching, prefix searches, range scans)
* `RecoveryTest` (Clean log replay, torn-write repair, truncation forcing)
* `RecoveryHardeningTest` (Pre-allocation size rejection, corruption detection, sequence integrity)
* `WalWriterReaderTest` (Binary layout, Unicode support, empty string vs null preservation, pre-write entry size bounds)
* `ApiControllerTest` (REST API controller verification)
