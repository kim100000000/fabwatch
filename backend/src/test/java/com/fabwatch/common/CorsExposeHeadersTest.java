package com.fabwatch.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CORS: 허용 Origin 응답에 Retry-After 가 노출되고, 허용되지 않은 Origin 은 거절된다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CorsExposeHeadersTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("허용 Origin 요청 응답에 Access-Control-Expose-Headers: Retry-After")
    void 허용_origin_expose_헤더() throws Exception {
        mockMvc.perform(get("/actuator/health").header(HttpHeaders.ORIGIN, "http://localhost:5173"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, "Retry-After"));
    }

    @Test
    @DisplayName("허용되지 않은 Origin 은 403")
    void 비허용_origin() throws Exception {
        mockMvc.perform(get("/actuator/health").header(HttpHeaders.ORIGIN, "https://evil.example"))
                .andExpect(status().isForbidden());
    }
}
