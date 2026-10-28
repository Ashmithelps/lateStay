package com.latestay.conversation.llm;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import org.springframework.web.client.RestClientException;

final class HttpErrors {

    private HttpErrors() {
    }

    static LlmUnavailableException unavailable(String provider, RestClientException e) {
        return new LlmUnavailableException(provider + " request failed: " + e.getMessage(), e, isTimeout(e));
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }
}
