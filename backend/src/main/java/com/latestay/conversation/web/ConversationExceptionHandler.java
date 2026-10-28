package com.latestay.conversation.web;

import com.latestay.conversation.UnknownConversationException;
import com.latestay.negotiation.SessionClosedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ConversationExceptionHandler {

    @ExceptionHandler(UnknownConversationException.class)
    ProblemDetail notFound(UnknownConversationException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(SessionClosedException.class)
    ProblemDetail closed(SessionClosedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setProperty("sessionStatus", e.status());
        return problem;
    }
}
