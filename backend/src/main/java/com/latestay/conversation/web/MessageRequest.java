package com.latestay.conversation.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A traveller's chat message: at most 500 characters (the web app's input enforces the same). */
record MessageRequest(@NotBlank @Size(max = MessageRequest.MAX_LENGTH) String text) {

    static final int MAX_LENGTH = 500;
}
