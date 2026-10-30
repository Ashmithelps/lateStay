package com.latestay.owner;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The owner page's sign-in ({@code latestay.owner.*}).
 *
 * @param secret signs the ls_owner cookie (OWNER_SESSION_SECRET, at least 32 characters in production)
 * @param linkValidity how long a WhatsApp sign-in link works (once)
 * @param session how long a signed-in owner page stays signed in
 * @param cookieSecure Secure flag on the cookie (off only for local http)
 */
@ConfigurationProperties("latestay.owner")
public record OwnerProperties(
        @DefaultValue("") String secret,
        @DefaultValue("15m") Duration linkValidity,
        @DefaultValue("30d") Duration session,
        @DefaultValue("true") boolean cookieSecure) {
}
