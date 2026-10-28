package com.latestay.admin;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.latestay.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** Without ADMIN_USERNAME / ADMIN_PASSWORD there is no admin user at all. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AdminDisabledIT {

    @Autowired
    MockMvc mvc;

    @Test
    void everyAdminRequestIsRefused() throws Exception {
        mvc.perform(get("/api/admin/hotels")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/hotels").with(httpBasic("", ""))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/hotels").with(httpBasic("admin", "admin"))).andExpect(status().isUnauthorized());
    }
}
