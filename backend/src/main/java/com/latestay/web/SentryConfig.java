package com.latestay.web;

import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.protocol.Message;
import io.sentry.protocol.SentryException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sentry (errors only; on when SENTRY_DSN is set). Default PII sending is off in application.yml; this also masks
 * phone numbers in messages and exception values, and drops request bodies and cookies.
 */
@Configuration
class SentryConfig {

    @Bean
    SentryOptions.BeforeSendCallback scrubPersonalData() {
        return (event, hint) -> scrub(event);
    }

    static SentryEvent scrub(SentryEvent event) {
        Message message = event.getMessage();
        if (message != null) {
            message.setMessage(Pii.scrub(message.getMessage()));
            message.setFormatted(Pii.scrub(message.getFormatted()));
        }
        if (event.getExceptions() != null) {
            for (SentryException exception : event.getExceptions()) {
                exception.setValue(Pii.scrub(exception.getValue()));
            }
        }
        if (event.getRequest() != null) {
            event.getRequest().setData(null);
            event.getRequest().setCookies(null);
            event.getRequest().setQueryString(Pii.scrub(event.getRequest().getQueryString()));
        }
        event.setUser(null);
        return event;
    }
}
