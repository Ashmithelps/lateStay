package com.latestay.config;

import com.latestay.web.RateLimiter;
import java.util.Arrays;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
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

    public WebConfig(@Value("${latestay.web-origin}") String webOrigin, ObjectProvider<RateLimiter> rateLimiter) {
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
                        "/api/bookings"));
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(webOrigins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type", "Authorization", "X-Requested-With");
    }
}
