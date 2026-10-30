package com.latestay.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Token buckets on the traveller's write endpoints: negotiation messages and accepts (per session and per IP),
 * opening negotiations and creating bookings (per IP). Over the limit: 429 with Retry-After. The client IP comes
 * from the proxy's X-Forwarded-For (server.forward-headers-strategy).
 */
@Component
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimiter implements HandlerInterceptor {

    private static final Pattern MESSAGE = Pattern.compile("^/api/negotiations/([^/]+)/(?:messages|accept)$");
    private static final Pattern CODE_CHECK = Pattern.compile("^/api/phone-verifications/[^/]+/check$");

    private final RateLimitProperties limits;
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .maximumSize(200_000)
            .build();

    public RateLimiter(RateLimitProperties limits) {
        this.limits = limits;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!limits.enabled() || !"POST".equals(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        String ip = request.getRemoteAddr();
        List<Limit> checks;
        Matcher message = MESSAGE.matcher(path);
        if (message.matches()) {
            checks = List.of(new Limit("msg-session:" + message.group(1), limits.messagesPerSession()),
                    new Limit("msg-ip:" + ip, limits.messagesPerIp()));
        } else if (path.equals("/api/negotiations")) {
            checks = List.of(new Limit("open-ip:" + ip, limits.negotiationsPerIp()));
        } else if (path.equals("/api/bookings")) {
            checks = List.of(new Limit("book-ip:" + ip, limits.bookingsPerIp()));
        } else if (path.equals("/api/phone-verifications")) {
            checks = List.of(new Limit("code-ip:" + ip, limits.codesPerIpPerHour(), Duration.ofHours(1)));
        } else if (path.equals("/api/events") || path.endsWith("/attribution") || path.endsWith("/review")
                || path.endsWith("/share") || path.equals("/api/devices")) {
            checks = List.of(new Limit("events-ip:" + ip, limits.eventsPerIp()));
        } else if (path.equals("/api/owner/session")) {
            checks = List.of(new Limit("owner-ip:" + ip, limits.ownerSignInsPerIpPerHour(), Duration.ofHours(1)));
        } else if (CODE_CHECK.matcher(path).matches()) {
            checks = List.of(new Limit("check-ip:" + ip, limits.codeChecksPerIpPerHour(), Duration.ofHours(1)));
        } else {
            return true;
        }
        long waitNanos = 0;
        for (Limit limit : checks) {
            ConsumptionProbe probe = bucket(limit).tryConsumeAndReturnRemaining(1);
            if (!probe.isConsumed()) {
                waitNanos = Math.max(waitNanos, probe.getNanosToWaitForRefill());
            }
        }
        if (waitNanos == 0) {
            return true;
        }
        long seconds = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(waitNanos) + 1);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(seconds));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,"
                + "\"detail\":\"Too many requests. Try again in " + seconds + " seconds.\",\"retryAfter\":" + seconds
                + "}");
        return false;
    }

    private record Limit(String key, long perPeriod, Duration period) {
        Limit(String key, long perMinute) {
            this(key, perMinute, Duration.ofMinutes(1));
        }
    }

    private Bucket bucket(Limit limit) {
        return buckets.get(limit.key(), key -> Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(limit.perPeriod())
                        .refillGreedy(limit.perPeriod(), limit.period()).build())
                .build());
    }
}
