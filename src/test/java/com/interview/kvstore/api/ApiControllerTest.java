package com.interview.kvstore.api;

import com.interview.kvstore.core.StorageEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ApiControllerTest {

    private Path walFile;
    private StorageEngine engine;
    private KeyValueController kvController;
    private AdminController adminController;

    @BeforeEach
    void setUp() throws Exception {
        walFile = Files.createTempFile("api-test", ".wal");
        Files.deleteIfExists(walFile);
        engine = new StorageEngine(walFile);
        kvController = new KeyValueController(engine);
        adminController = new AdminController(engine);
    }

    @AfterEach
    void tearDown() throws Exception {
        engine.close();
        Files.deleteIfExists(walFile);
    }

    @Test
    void putGetAndDeleteViaController() throws Exception {
        // PUT
        ResponseEntity<?> putRes = kvController.put("item1", Map.of("value", "phone"));
        assertEquals(HttpStatus.OK, putRes.getStatusCode());

        // GET
        ResponseEntity<?> getRes = kvController.get("item1");
        assertEquals(HttpStatus.OK, getRes.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) getRes.getBody();
        assertNotNull(body);
        assertEquals("phone", body.get("value"));

        // DELETE
        ResponseEntity<?> delRes = kvController.delete("item1");
        assertEquals(HttpStatus.OK, delRes.getStatusCode());

        // GET missing
        ResponseEntity<?> missRes = kvController.get("item1");
        assertEquals(HttpStatus.NOT_FOUND, missRes.getStatusCode());
    }

    @Test
    void batchWriteAndPrefixQueryViaController() throws Exception {
        KeyValueController.BatchRequest req = new KeyValueController.BatchRequest(
                Map.of("user:101", "Alice", "user:102", "Bob", "config:theme", "dark"),
                List.of()
        );

        ResponseEntity<?> batchRes = kvController.batchWrite(req);
        assertEquals(HttpStatus.OK, batchRes.getStatusCode());

        // Prefix Query
        ResponseEntity<?> queryRes = kvController.query("user:", null, null, 10);
        assertEquals(HttpStatus.OK, queryRes.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> queryBody = (Map<String, Object>) queryRes.getBody();
        assertNotNull(queryBody);
        assertEquals(2, queryBody.get("count"));

        @SuppressWarnings("unchecked")
        Map<String, String> keys = (Map<String, String>) queryBody.get("keys");
        assertEquals("Alice", keys.get("user:101"));
        assertEquals("Bob", keys.get("user:102"));
    }

    @Test
    void adminStatsAndCompaction() {
        ResponseEntity<?> healthRes = adminController.health();
        assertEquals(HttpStatus.OK, healthRes.getStatusCode());

        ResponseEntity<?> statsRes = adminController.stats();
        assertEquals(HttpStatus.OK, statsRes.getStatusCode());

        ResponseEntity<?> compactRes = adminController.compact();
        assertEquals(HttpStatus.OK, compactRes.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> compactBody = (Map<String, Object>) compactRes.getBody();
        assertNotNull(compactBody);
        assertEquals("compacted", compactBody.get("status"));

        ResponseEntity<?> integrityRes = adminController.integrity();
        assertEquals(HttpStatus.OK, integrityRes.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> integrityBody = (Map<String, Object>) integrityRes.getBody();
        assertNotNull(integrityBody);
        assertEquals("DISABLED", integrityBody.get("status"));
        assertEquals(false, integrityBody.get("integrityEnabled"));
    }
}
