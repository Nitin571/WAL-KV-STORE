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
        System.out.println("Commands: put <key> <value> | get <key> | delete <key> | size | exit");

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

                String[] parts = line.split("\\s+", 3);
                String cmd = parts[0].toLowerCase();

                try {
                    switch (cmd) {
                        case "put" -> {
                            if (parts.length < 3) System.out.println("Usage: put <key> <value>");
                            else { engine.put(parts[1], parts[2]); System.out.println("OK (durably written)"); }
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
