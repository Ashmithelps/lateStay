package com.latestay.dev.negotiation;

import com.latestay.negotiation.EngineDecision;
import com.latestay.negotiation.SessionStatus;

record TurnResponse(EngineDecision decision, SessionStatus status) {
}
