package com.latestay.conversation.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

record MessageRequest(@NotBlank @Size(max = 4000) String text) {
}
