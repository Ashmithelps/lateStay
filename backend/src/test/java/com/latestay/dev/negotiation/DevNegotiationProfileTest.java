package com.latestay.dev.negotiation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.latestay.config.NegotiationConfig;
import com.latestay.conversation.ConversationStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Without the 'local' profile the dev API does not exist. */
@WebMvcTest(DevNegotiationController.class)
@Import({NegotiationConfig.class, ConversationStore.class})
class DevNegotiationProfileTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ApplicationContext context;

    @Test
    void devApiIsAbsentOutsideLocalProfile() throws Exception {
        assertThat(context.getBeanNamesForType(DevNegotiationController.class)).isEmpty();

        mvc.perform(post("/api/dev/negotiations").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }
}
