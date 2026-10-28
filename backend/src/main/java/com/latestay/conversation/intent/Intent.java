package com.latestay.conversation.intent;

import java.util.Objects;

/**
 * What the traveller's message means.
 *
 * @param amount rupees, for COUNTER (and optionally ACCEPT); null otherwise
 * @param question the question in plain words, for QUESTION; null otherwise
 */
public record Intent(IntentType type, Long amount, String question, Register register) {

    public Intent {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(register, "register");
    }
}
