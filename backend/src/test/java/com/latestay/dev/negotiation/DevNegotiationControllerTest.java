package com.latestay.dev.negotiation;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.latestay.config.NegotiationConfig;
import com.latestay.conversation.ConversationStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest(DevNegotiationController.class)
@ActiveProfiles("local")
@Import({NegotiationConfig.class, ConversationStore.class})
class DevNegotiationControllerTest {

    private static final String CREATE_BODY = """
            {"listPrice": 1400, "floorPrice": 700, "slotType": "LATE_NIGHT", "arrival": "23:30"}
            """;

    @Autowired
    private MockMvc mvc;

    @Test
    void createsSessionWithOpeningOfferAndHidesFloor() throws Exception {
        create()
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.currentOffer").value(1000))
                .andExpect(jsonPath("$.round").value(0))
                .andExpect(jsonPath("$.history", hasSize(1)))
                .andExpect(jsonPath("$.history[0].decision.action").value("OFFER"))
                .andExpect(jsonPath("$.history[0].decision.reasonCode").value("LATE_HOUR"))
                .andExpect(jsonPath("$.floorPrice").doesNotExist());
    }

    @Test
    void playsTurnsUntilAgreed() throws Exception {
        String id = createdId();

        turn(id, """
                {"type": "COUNTER", "amount": 500}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision.action").value("OFFER"))
                .andExpect(jsonPath("$.decision.amount").value(900))
                .andExpect(jsonPath("$.decision.reasonCode").value("COUNTER_TOO_LOW"))
                .andExpect(jsonPath("$.status").value("OPEN"));

        turn(id, """
                {"type": "ACCEPT"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision.action").value("AGREE"))
                .andExpect(jsonPath("$.decision.amount").value(900))
                .andExpect(jsonPath("$.status").value("AGREED"));

        turn(id, """
                {"type": "COUNTER", "amount": 800}
                """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.sessionStatus").value("AGREED"));
    }

    @Test
    void rejectsBadInput() throws Exception {
        String id = createdId();

        turn(id, """
                {"type": "COUNTER"}
                """).andExpect(status().isBadRequest());
        turn(id, """
                {"type": "HAGGLE"}
                """).andExpect(status().isBadRequest());
        turn(id, """
                {"type": "COUNTER", "amount": -5}
                """).andExpect(status().isBadRequest());
        mvc.perform(post("/api/dev/negotiations").contentType(MediaType.APPLICATION_JSON).content("""
                        {"listPrice": 1400, "floorPrice": 700, "slotType": "LATE_NIGHT", "arrival": "18:00"}
                        """))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/dev/negotiations").contentType(MediaType.APPLICATION_JSON).content("""
                        {"listPrice": 1400, "slotType": "LATE_NIGHT", "arrival": "23:30"}
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownSessionIsNotFound() throws Exception {
        turn("nope", """
                {"type": "ACCEPT"}
                """).andExpect(status().isNotFound());
    }

    private ResultActions create() throws Exception {
        return mvc.perform(post("/api/dev/negotiations").contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY));
    }

    private String createdId() throws Exception {
        String body = create().andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private ResultActions turn(String id, String body) throws Exception {
        return mvc.perform(post("/api/dev/negotiations/{id}/turns", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
