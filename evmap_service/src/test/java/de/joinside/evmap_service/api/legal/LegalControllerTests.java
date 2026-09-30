package de.joinside.evmap_service.api.legal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LegalControllerTests {
    @Test
    @DisplayName("hands out the configured privacy policy URL")
    void configuredUrl() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new LegalController(" https://evmap.example/privacy ")).build();

        mvc.perform(get("/api/v1/legal")).andExpect(status().isOk())
                .andExpect(jsonPath("$.privacyPolicyUrl").value("https://evmap.example/privacy"));
    }

    @Test
    @DisplayName("without a configured URL the field is absent, so clients show no link")
    void noUrl() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new LegalController("")).build();

        mvc.perform(get("/api/v1/legal")).andExpect(status().isOk()).andExpect(jsonPath("$.privacyPolicyUrl").doesNotExist());
    }
}
