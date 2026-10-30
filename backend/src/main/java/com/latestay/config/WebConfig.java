package com.latestay.config;

import com.latestay.owner.OwnerHotelResolver;
import com.latestay.owner.OwnerSessions;
import com.latestay.web.RateLimiter;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for the web app's exact origins only ({@code WEB_ORIGIN}, comma-separated, no wildcards), and the rate
 * limiter on the traveller's write endpoints.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final String[] webOrigins;
    private final ObjectProvider<RateLimiter> rateLimiter;
    private final ObjectProvider<OwnerSessions> ownerSessions;

    public WebConfig(@Value("${latestay.web-origin}") String webOrigin, ObjectProvider<RateLimiter> rateLimiter,
            ObjectProvider<OwnerSessions> ownerSessions) {
        this.ownerSessions = ownerSessions;
        this.webOrigins = Arrays.stream(webOrigin.split(",")).map(String::trim).filter(o -> !o.isEmpty())
                .peek(o -> {
                    if (o.contains("*")) {
                        throw new IllegalArgumentException("WEB_ORIGIN must list exact origins, not " + o);
                    }
                })
                .toArray(String[]::new);
        this.rateLimiter = rateLimiter;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        rateLimiter.ifAvailable(limiter -> registry.addInterceptor(limiter)
                .addPathPatterns("/api/negotiations", "/api/negotiations/*/messages", "/api/negotiations/*/accept",
                        "/api/bookings", "/api/phone-verifications", "/api/phone-verifications/*/check",
                        "/api/owner/session", "/api/events", "/api/stay-requests/*/attribution",
                        "/api/bookings/*/review", "/api/bookings/*/share", "/api/devices"));
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        ownerSessions.ifAvailable(sessions -> resolvers.add(new OwnerHotelResolver(sessions)));
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(webOrigins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type", "Authorization", "X-Requested-With")
                // The "phone verified on this device" cookie travels with booking requests, and the owner page's
                // sign-in cookie with owner requests.
                .allowCredentials(true);
    }
}
