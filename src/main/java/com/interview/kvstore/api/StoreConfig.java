package com.interview.kvstore.api;

import com.interview.kvstore.core.StorageEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Wires StorageEngine as a singleton Spring bean, unchanged from the
 * version that was compiled and crash-tested directly (CLI, simulated
 * torn write, and a real kill -9). Recovery runs once here, at
 * application startup, via StorageEngine's constructor.
 */
@Configuration
public class StoreConfig {

    @Bean(destroyMethod = "close")
    public StorageEngine storageEngine(
            @Value("${wal.file:./wal-data/store.wal}") String walFilePath) throws Exception {
        Path walFile = Path.of(walFilePath);
        if (walFile.getParent() != null) {
            Files.createDirectories(walFile.getParent());
        }
        return new StorageEngine(walFile);
    }
}
