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
* **Health Check:** `GET /admin/health`
* **Telemetry & Metrics:** `GET /admin/stats`
  ```json
  {
    "keyCount": 1500,
    "walFileSizeBytes": 45280,
    "lastCompactedAt": 1726058000,
    "recoveredEntriesCount": 1500,
    "tornWriteDetectedOnStartup": false,
    "currentSequenceNumber": 1500
  }
  ```
* **Trigger Compaction:** `POST /admin/compact`
  ```json
  {
    "status": "compacted",
    "bytesBefore": 2548200,
    "bytesAfter": 32100,
    "bytesReclaimed": 2516100,
    "keysRetained": 1500
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

---

## Test Suite

All 28 comprehensive unit tests pass with zero warnings:

```bash
mvn test
```

* `StorageEngineTest` (CRUD, persistence across reboots)
* `CompactionTest` (Space reclamation, atomic file replacement, auto-compaction)
* `BatchAndScanTest` (Group commit batching, prefix searches, range scans)
* `RecoveryTest` (Clean log replay, torn-write repair, truncation forcing)
* `RecoveryHardeningTest` (Pre-allocation size rejection, corruption detection, sequence integrity)
* `WalWriterReaderTest` (Binary layout, Unicode support, empty string vs null preservation)
* `ApiControllerTest` (REST API verification)
