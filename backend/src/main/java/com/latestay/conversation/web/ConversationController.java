package com.latestay.conversation.web;

import com.latestay.conversation.ConversationReply;
import com.latestay.conversation.ConversationService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Traveller chat for a negotiation. Local profile only until hotels are stored. */
@RestController
@Profile("local")
@RequestMapping("/api/negotiations")
class ConversationController {

    private final ConversationService conversations;

    ConversationController(ConversationService conversations) {
        this.conversations = conversations;
    }

    @PostMapping("/{id}/messages")
    ConversationReply message(@PathVariable String id, @Valid @RequestBody MessageRequest request) {
        return conversations.handle(id, request.text());
    }
}
