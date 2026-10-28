package com.latestay.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Only /api/admin/** needs credentials (HTTP basic, from ADMIN_USERNAME / ADMIN_PASSWORD). Without both set,
 * there is no admin user and every admin request is refused. Everything else is public.
 */
@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().permitAll())
                .httpBasic(basic -> basic.authenticationEntryPoint(SecurityConfig::unauthorized))
                .build();
    }

    /**
     * 401 for admin calls. The admin panel's fetches send X-Requested-With, and get no WWW-Authenticate header so
     * the browser shows the panel's own sign-in form instead of its native password prompt.
     */
    private static void unauthorized(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException e) throws IOException {
        if (request.getHeader("X-Requested-With") == null) {
            response.setHeader("WWW-Authenticate", "Basic realm=\"LateStay admin\"");
        }
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService adminUsers(@Value("${latestay.admin.username:}") String username,
            @Value("${latestay.admin.password:}") String password, PasswordEncoder encoder) {
        if (username.isBlank() || password.isBlank()) {
            log.warn("ADMIN_USERNAME / ADMIN_PASSWORD not set: admin API disabled");
            return new InMemoryUserDetailsManager();
        }
        return new InMemoryUserDetailsManager(
                User.withUsername(username).password(encoder.encode(password)).roles("ADMIN").build());
    }
}
