package com.interview.kvstore.tools;

import com.interview.kvstore.core.StorageEngine;
import com.interview.kvstore.wal.WalEntry;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Demonstrates a REAL mid-write crash, not just a crash landing between
 * two completed writes.
 *
 * Why this is needed: WalWriter.appendPut() completes in microseconds --
 * far too fast for a human to kill -9 in the middle of it. If you just
 * kill -9 between two normal put commands, you're not testing anything
 * interesting; the write had already fully completed and been fsynced.
 *
 * This tool manually recreates one entry's bytes and writes them to the
 * file in TWO separate chunks with a deliberate sleep in between, and
 * only calls force() after the second chunk. That sleep is a real,
 * multi-second window where the file on disk genuinely contains a
 * partial, torn entry -- not a simulation. Kill -9 during that window and
 * you've created the exact failure this project defends against.
 *
 * Usage: java SlowWriteDemo <wal-file> <sleep-seconds>
 */
public final class SlowWriteDemo {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: java SlowWriteDemo <wal-file> [sleepSeconds=8]");
            return;
        }
        Path walFile = Path.of(args[0]);
        int sleepSeconds = args.length > 1 ? Integer.parseInt(args[1]) : 8;

        // First, write two NORMAL, fully-completed entries so we have a
        // baseline of good data that should survive no matter what.
        try (StorageEngine engine = new StorageEngine(walFile)) {
            engine.put("goodkey1", "written-before-slow-write");
            engine.put("goodkey2", "also-written-before");
        }
        System.out.println("Wrote 2 normal, complete entries first.");

        long pid = ProcessHandle.current().pid();
        System.out.println("PID = " + pid);

        // Now build the bytes for a THIRD entry manually, and write it in
        // two pieces with a real sleep in between -- this is the genuine
        // mid-write window.
        WalEntry thirdEntry = new WalEntry(2, WalEntry.OpType.PUT, "slowkey", "this-write-will-be-interrupted");
        byte[] fullBytes = thirdEntry.serialize();
        int splitPoint = fullBytes.length / 2; // cut it in half

        try (FileChannel ch = FileChannel.open(walFile, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            // Write only the FIRST HALF of the entry's bytes.
            ByteBuffer firstHalf = ByteBuffer.wrap(fullBytes, 0, splitPoint);
            ch.write(firstHalf);
            ch.force(true); // flush what we have so far -- this is genuinely on disk now, half an entry

            System.out.println("Wrote " + splitPoint + " of " + fullBytes.length +
                    " bytes of a third entry, then flushed to disk.");
            System.out.println("The file RIGHT NOW on disk contains a real, genuine torn write.");
            System.out.println("You have " + sleepSeconds + " seconds -- run: kill -9 " + pid);
            System.out.println("(If you don't kill it, the write will complete normally and nothing torn will remain.)");

            Thread.sleep(sleepSeconds * 1000L);

            // If we get here, nobody killed us -- finish the write normally.
            ByteBuffer secondHalf = ByteBuffer.wrap(fullBytes, splitPoint, fullBytes.length - splitPoint);
            ch.write(secondHalf);
            ch.force(true);
            System.out.println("Not killed in time -- completed the write normally. Nothing torn to recover.");
        }
    }
}
