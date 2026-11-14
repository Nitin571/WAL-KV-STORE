package com.interview.kvstore.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end integration tests over the full Spring MVC servlet stack using MockMvc.
 * Verifies real URL routing, parameter binding, Jackson serialization, input validation,
 * and HTTP response codes.
 */
@SpringBootTest
@AutoConfigureMockMvc
class KeyValueControllerHttpIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void testPutGetAndDeleteFlow() throws Exception {
        // 1. PUT
        mockMvc.perform(put("/keys/http-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"Alice\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("http-user"))
                .andExpect(jsonPath("$.value").value("Alice"))
                .andExpect(jsonPath("$.status").value("durably written"));

        // 2. GET
        mockMvc.perform(get("/keys/http-user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("http-user"))
                .andExpect(jsonPath("$.value").value("Alice"));

        // 3. DELETE
        mockMvc.perform(delete("/keys/http-user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("durably deleted"));

        // 4. GET after DELETE should return 404
        mockMvc.perform(get("/keys/http-user"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("key not found"));
    }

    @Test
    void testBatchWriteAndPrefixQuery() throws Exception {
        // Seed multiple keys via atomic batch
        String batchJson = """
                {
                    "puts": {
                        "account:101": "active",
                        "account:102": "suspended",
                        "other:999": "ignored"
                    },
                    "deletes": []
                }
                """;

        mockMvc.perform(post("/keys/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operationCount").value(3))
                .andExpect(jsonPath("$.status").value("durably written"));

        // Query with prefix
        mockMvc.perform(get("/keys")
                        .param("prefix", "account:")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.keys['account:101']").value("active"))
                .andExpect(jsonPath("$.keys['account:102']").value("suspended"));
    }

    @Test
    void testRangeQuery() throws Exception {
        mockMvc.perform(put("/keys/k1").contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"v1\"}"));
        mockMvc.perform(put("/keys/k2").contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"v2\"}"));
        mockMvc.perform(put("/keys/k3").contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"v3\"}"));

        mockMvc.perform(get("/keys")
                        .param("from", "k1")
                        .param("to", "k2")
                        .param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.keys.k1").value("v1"))
                .andExpect(jsonPath("$.keys.k2").value("v2"));
    }

    @Test
    void testMalformedJsonReturnsBadRequest() throws Exception {
        // Syntactically invalid JSON
        mockMvc.perform(put("/keys/bad-json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Malformed or unreadable JSON request body"));
    }

    @Test
    void testMissingValueFieldReturnsBadRequest() throws Exception {
        mockMvc.perform(put("/keys/test-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"otherField\":\"no-value\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("Request body must include \"value\"")));
    }

    @Test
    void testInvalidRangeReturnsBadRequest() throws Exception {
        // from ("z") > to ("a")
        mockMvc.perform(get("/keys")
                        .param("from", "z")
                        .param("to", "a"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("Invalid range")));
    }

    @Test
    void testInvalidLimitReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/keys")
                        .param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("Limit must be between 1 and 1000")));

        mockMvc.perform(get("/keys")
                        .param("limit", "5000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("Limit must be between 1 and 1000")));
    }

    @Test
    void testBatchWriteWithNullValueReturnsBadRequest() throws Exception {
        String invalidBatchJson = """
                {
                    "puts": {
                        "user:bad": null
                    }
                }
                """;

        mockMvc.perform(post("/keys/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBatchJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("Batch PUT value cannot be null")));
    }

    @Test
    void testAdminEndpoints() throws Exception {
        // 1. Health check is public (no authentication required)
        mockMvc.perform(get("/admin/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.walWritable").value(true));

        // 2. Telemetry stats without token should return 401 Unauthorized
        mockMvc.perform(get("/admin/stats"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error", containsString("Unauthorized")));

        // 3. Compaction without token should return 401 Unauthorized
        mockMvc.perform(post("/admin/compact"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error", containsString("Unauthorized")));

        // 4. Calling with invalid token should return 401 Unauthorized
        mockMvc.perform(get("/admin/stats")
                        .header("X-Admin-Token", "wrong-secret"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error", containsString("Unauthorized")));

        // 5. Telemetry stats with valid X-Admin-Token header
        mockMvc.perform(get("/admin/stats")
                        .header("X-Admin-Token", "secret-admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keyCount").isNumber())
                .andExpect(jsonPath("$.walFileSizeBytes").isNumber())
                .andExpect(jsonPath("$.totalWriteOps").isNumber())
                .andExpect(jsonPath("$.avgWriteLatencyMicros").isNumber())
                .andExpect(jsonPath("$.compactionCount").isNumber());

        // 6. Compaction with valid Authorization: Bearer header
        mockMvc.perform(post("/admin/compact")
                        .header("Authorization", "Bearer secret-admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("compacted"))
                .andExpect(jsonPath("$.keysRetained").isNumber());
    }
}
