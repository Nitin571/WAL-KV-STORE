package com.interview.kvstore.tools;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Appends a deliberately incomplete, garbage "entry" directly to a WAL
 * file, bypassing WalWriter's normal fsync-then-ack discipline. This
 * simulates what a real crash-mid-append looks like on disk: a length
 * prefix claiming N more bytes than actually follow it.
 *
 * Why have this at all, if we can just kill -9 a real process? Because
 * kill -9 depends on timing luck (you have to catch the process at the
 * right instant), which makes it unsuitable for an automated test suite.
 * This gives you a deterministic, repeatable torn write every time --
 * useful for CI, and for RecoveryTest in the test package.
 */
public final class CrashSimulator {

    private CrashSimulator() {}

    /** Appends a torn write to the given WAL file. The file should already exist or be creatable. */
    public static void appendTornWrite(Path walFile) throws Exception {
        try (FileChannel ch = FileChannel.open(walFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer garbage = ByteBuffer.allocate(10);
            garbage.putInt(9999);      // claims a huge body length that doesn't actually exist in the file
            garbage.put((byte) 1);
            garbage.put((byte) 2);
            garbage.put((byte) 3);
            garbage.put((byte) 4);
            garbage.put((byte) 5);
            garbage.flip();
            ch.write(garbage);
            ch.force(true);
        }
    }
}
