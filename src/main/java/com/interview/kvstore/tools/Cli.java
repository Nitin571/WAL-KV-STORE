package com.interview.kvstore.tools;

import com.interview.kvstore.core.StorageEngine;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;

/**
 * Interactive shell over StorageEngine. Every command goes through the
 * exact same durable put()/get()/delete() path used everywhere else --
 * this is purely a friendlier way to drive the engine by hand, not a
 * separate code path. Prints its own PID on startup so you can kill -9 it
 * from another terminal mid-session and verify recovery for real.
 */
public final class Cli {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: java Cli <wal-file-path>");
            return;
        }
        Path walFile = Path.of(args[0]);

        long pid = ProcessHandle.current().pid();
        System.out.println("WAL-backed KV store. PID = " + pid +
                " (kill -9 " + pid + " from another terminal to test crash recovery live).");
        System.out.println("Commands:");
        System.out.println("  put <key> <value>            - Durably write a key-value pair");
        System.out.println("  get <key>                    - Read a key value");
        System.out.println("  delete <key>                 - Durably delete a key");
        System.out.println("  scan <prefix> [limit]        - Prefix search (e.g. scan user:)");
        System.out.println("  range <from> <to> [limit]    - Range search (e.g. range a m)");
        System.out.println("  compact                      - Trigger atomic log compaction");
        System.out.println("  stats                        - Display telemetry and file size");
        System.out.println("  size                         - Active key count");
        System.out.println("  exit / quit                  - Exit CLI");

        try (StorageEngine engine = new StorageEngine(walFile)) {
            var recovery = engine.getLastRecoveryResult();
            System.out.println("[startup] Recovered " + recovery.recoveredEntries.size() + " entries" +
                    (recovery.tornWriteFound ? " (discarded a torn write: " + recovery.tornWriteDescription + ")" : "") +
                    ". Store has " + engine.size() + " keys.");

            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            System.out.print("> ");
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) { System.out.print("> "); continue; }

                String[] parts = line.split("\\s+");
                String cmd = parts[0].toLowerCase();

                try {
                    switch (cmd) {
                        case "put" -> {
                            if (parts.length < 3) System.out.println("Usage: put <key> <value>");
                            else {
                                String val = line.substring(line.indexOf(parts[1]) + parts[1].length()).trim();
                                engine.put(parts[1], val);
                                System.out.println("OK (durably written)");
                            }
                        }
                        case "get" -> {
                            if (parts.length < 2) System.out.println("Usage: get <key>");
                            else {
                                String v = engine.get(parts[1]);
                                System.out.println(v == null ? "(not found)" : v);
                            }
                        }
                        case "delete" -> {
                            if (parts.length < 2) System.out.println("Usage: delete <key>");
                            else { engine.delete(parts[1]); System.out.println("OK (deletion durably logged)"); }
                        }
                        case "scan" -> {
                            if (parts.length < 2) System.out.println("Usage: scan <prefix> [limit]");
                            else {
                                int limit = parts.length > 2 ? Integer.parseInt(parts[2]) : 50;
                                var results = engine.scan(parts[1], limit);
                                System.out.println("Found " + results.size() + " matching keys:");
                                results.forEach((k, v) -> System.out.println("  " + k + " = " + v));
                            }
                        }
                        case "range" -> {
                            if (parts.length < 3) System.out.println("Usage: range <from> <to> [limit]");
                            else {
                                int limit = parts.length > 3 ? Integer.parseInt(parts[3]) : 50;
                                var results = engine.range(parts[1], parts[2], limit);
                                System.out.println("Found " + results.size() + " keys in range:");
                                results.forEach((k, v) -> System.out.println("  " + k + " = " + v));
                            }
                        }
                        case "compact" -> {
                            var res = engine.compact();
                            System.out.println("Compaction completed: " + res.bytesBefore() + " bytes -> " +
                                    res.bytesAfter() + " bytes (reclaimed " + res.bytesReclaimed() +
                                    " bytes, retained " + res.keysRetained() + " keys)");
                        }
                        case "stats" -> {
                            var s = engine.getStats();
                            System.out.println("Engine Statistics:");
                            System.out.println("  Active keys:            " + s.keyCount());
                            System.out.println("  WAL file size:          " + s.walFileSizeBytes() + " bytes");
                            System.out.println("  Current sequence num:   " + s.currentSequenceNumber());
                            System.out.println("  Startup recovered keys: " + s.recoveredEntriesCount());
                            System.out.println("  Total write operations: " + s.totalWriteOps());
                            System.out.println("  Avg write latency (µs): " + s.avgWriteLatencyMicros());
                            System.out.println("  Compaction count:       " + s.compactionCount());
                        }
                        case "size" -> System.out.println(engine.size() + " keys currently in store");
                        case "exit", "quit" -> { System.out.println("Bye."); return; }
                        default -> System.out.println("Unknown command: " + cmd);
                    }
                } catch (Exception e) {
                    System.out.println("Error: " + e.getMessage());
                }
                System.out.print("> ");
            }
        }
    }
}
