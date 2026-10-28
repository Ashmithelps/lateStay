package com.latestay.conversation.web;

import com.latestay.conversation.ConversationReply;
import com.latestay.conversation.ConversationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Traveller chat for a negotiation. */
@RestController
@RequestMapping("/api/negotiations")
class ConversationController {

    private final ConversationService conversations;

    ConversationController(ConversationService conversations) {
        this.conversations = conversations;
    }

    /** The Accept button: agrees at the current offer through the engine, without parsing any text. */
    @PostMapping("/{id}/accept")
    ConversationReply accept(@PathVariable String id) {
        return conversations.accept(id);
    }

    @PostMapping("/{id}/messages")
    ConversationReply message(@PathVariable String id, @Valid @RequestBody MessageRequest request) {
        return conversations.handle(id, request.text());
    }
}
