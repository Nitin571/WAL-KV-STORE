package com.interview.kvstore.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "wal.file=./target/test-wal/integrity-spring.wal",
        "wal.integrity.enabled=true",
        "wal.integrity.secret=integration-test-secret-key-999"
})
class KeyValueControllerIntegrityHttpIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void testIntegrityApiEndpointWhenEnabled() throws Exception {
        // PUT a key to advance the sequence and generate a chained tag
        mockMvc.perform(put("/keys/secure-item")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"sensitive-data\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("durably written"));

        // GET /admin/integrity with admin token
        mockMvc.perform(get("/admin/integrity")
                        .header("X-Admin-Token", "secret-admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALID"))
                .andExpect(jsonPath("$.integrityEnabled").value(true))
                .andExpect(jsonPath("$.walFormatVersion").value(2))
                .andExpect(jsonPath("$.verifiedEntries").isNumber())
                .andExpect(jsonPath("$.lastSequenceNumber").isNumber())
                .andExpect(jsonPath("$.chainRootPrefix").isString())
                .andExpect(jsonPath("$.chainRootPrefix", hasLength(12)));
    }
}
