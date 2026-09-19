# WAL-Backed Key-Value Store (`com.interview.kvstore`)

A write-ahead log and crash-recovery engine built from raw `java.nio`
file I/O, hardened against corrupted/malicious input, with a Spring Boot
REST API on top and a JUnit 5 test suite underneath.

## What was actually verified vs. what wasn't

**Compiled and tested live, in a real environment, right before this zip
was created:**
- `exception`, `wal`, `recovery`, `core`, `tools` packages — compiled
  with `javac`.
- **18 JUnit 5 tests, all passing** (14 original behavior tests + 4 new
  tests specifically proving the hardening properties below).
- **A real, hard `kill -9`**, landing during a genuine partial-write
  window (see "The SlowWriteDemo" below) — not just between two
  completed writes — followed by recovery correctly discarding only the
  torn entry and keeping everything before it.

**Not compiled/run in this environment (needs your own machine):**
- The `api` package (Spring Boot). This sandbox has no network access to
  Maven Central. The code follows standard Spring Boot patterns and calls
  directly into the already-tested `StorageEngine` — run
  `mvn clean package` yourself and treat that as the real test.

## Hardening properties (and why each one matters)

These were added on top of the basic "detect a torn write and truncate"
behavior, because that basic version had real gaps:

1. **Record sizes are validated before any memory is allocated for
   them.** A corrupted length prefix (one stray bit flip turning a small
   number into something near `Integer.MAX_VALUE`) could otherwise make
   the reader try to allocate a multi-gigabyte buffer — crashing the
   process with an `OutOfMemoryError`, or acting as a denial-of-service
   vector against anything that reads an untrusted WAL file.
   `WalReader.MAX_ENTRY_SIZE_BYTES` (16 MiB) is checked first, before any
   `ByteBuffer.allocate()` call.

2. **Sequence numbers, key/value lengths, operation codes, checksums,
   and overall record structure are all validated**, not just the
   checksum. An unknown op code, a key/value length that runs past the
   record's actual bytes, or leftover unexplained trailing bytes are all
   caught explicitly (see `WalReader.parseBody`) instead of being trusted
   blindly or causing an unhandled `BufferUnderflowException` to bubble
   up as a crash.

3. **A torn write and a corrupted-but-complete record are treated
   completely differently.** A torn write (the file simply ends
   mid-entry) is the *expected*, safe artifact of a real crash — it's
   truncated away. A corrupted record (right number of bytes present, but
   checksum/structure/sequence is wrong) is a different, more serious
   situation — possibly disk-level corruption, a bug, or tampering — and
   is **never auto-truncated**. Silently truncating on corruption would
   risk deleting perfectly good data that happened to sit *after* the
   corrupted record in the file. Instead, `RecoveryManager` throws a
   `StorageException` and refuses to proceed, surfacing the problem for a
   human to investigate rather than guessing.

4. **Writes are properly locked, and durability is guaranteed before
   memory is updated.** `StorageEngine` wraps the "append to WAL, then
   apply to the in-memory map" sequence in a single lock
   (`writeLock` in `StorageEngine`), so under concurrent writers, memory
   state can never end up in a different order than what's durably on
   disk. Reads stay lock-free via `ConcurrentHashMap`, since a reader
   seeing a slightly-in-progress state is normal, not a correctness bug.

5. **Recovery's own truncation is itself forced to disk.** After
   discarding a torn write, `RecoveryManager.truncateFile` calls
   `force(true)` on the truncation itself — not just relying on the
   original writes having been fsynced. Without this, the truncation is
   only a metadata change the OS might not have flushed yet; a second
   crash immediately after recovery (before any new write triggers
   another fsync) could leave the torn bytes still physically present for
   the next recovery to trip over again.

## The SlowWriteDemo — a REAL mid-write crash, not a timing guess

A normal `put` completes in microseconds — far too fast to `kill -9` in
the middle of by hand. `SlowWriteDemo` deliberately splits one entry's
bytes into two chunks, writes and `force()`s the first half, then sleeps
for several real seconds before writing the second half. During that
sleep, the file **genuinely, physically** contains a partial entry on
disk — not a simulation.

```bash
java -cp target/classes com.interview.kvstore.tools.SlowWriteDemo ./wal-data/slowtest.wal 8
# it prints its PID and tells you the file right now contains a torn write
# from another terminal, within the given window:
kill -9 <PID>
# then recover:
java -cp target/classes com.interview.kvstore.tools.Cli ./wal-data/slowtest.wal
```

## Package layout

- **`exception`** — `StorageException` (base), `TornWriteException`,
  `CorruptedEntryException`.
- **`wal`** — `WalEntry`, `WalWriter` (append + fsync), `WalReader`
  (validated sequential read).
- **`recovery`** — `RecoveryManager` (replay, sequence validation,
  torn-vs-corrupt handling, forced truncation), `RecoveryResult`.
- **`core`** — `StorageEngine` (`put`/`get`/`delete`/`size`, locked
  writes, durable-before-memory ordering).
- **`tools`** — `CrashSimulator` (deterministic torn-write injection),
  `Cli` (interactive shell), `SlowWriteDemo` (real mid-write crash demo).
- **`api`** — Spring Boot REST layer (`PUT/GET/DELETE /keys/{key}`).
- **`test`** — JUnit 5 tests for all of the above, including
  `RecoveryHardeningTest` for the 5 properties listed above.

## Build and run

```bash
mvn clean package        # compiles everything, runs all 18 JUnit tests
mvn spring-boot:run       # starts the REST API on port 8080
```

Or without Maven, for just the plain-Java pieces:

```bash
javac -d out $(find src/main/java -name "*.java" ! -path "*/api/*")
java -cp out com.interview.kvstore.tools.Cli ./wal-data/store.wal
```

## Interview questions to be ready to defend

- Why validate the declared entry size *before* allocating a buffer for
  it, rather than just letting an oversized allocation fail naturally?
- Why must a torn write and a corrupted record be handled differently,
  instead of treating any unreadable entry the same way?
- Why does the outer `writeLock` in `StorageEngine` matter even though
  `WalWriter` is already internally synchronized?
- Why force the truncation itself to disk, not just the original writes?
- Why `force(true)` and not just `write()` for durability in general?
- Why CRC32 instead of a cryptographic hash?

## What to add next, in priority order

1. **Group commit** — batch concurrent writers into one fsync call
   instead of one per write, for real throughput gains.
2. **Checkpointing / compaction** — snapshot in-memory state periodically
   and truncate the WAL so recovery doesn't replay from the beginning of
   time as the log grows.
3. **Segment file rotation** — rotate the WAL at a size threshold instead
   of one ever-growing file.
4. **A recovery mode for corrupted files** — right now corruption halts
   startup entirely (by design, for safety); a real system might offer an
   explicit `--force-recover-past-corruption` flag for operators, logged
   loudly, rather than making that decision automatically.
