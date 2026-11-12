package ai.whitekey.config;

import ai.whitekey.service.AdminTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    public static final String BEARER_PREFIX = "Bearer ";
    public static final String ADMIN_PRINCIPAL = "admin";
    public static final String ERROR_ADMIN_TOKEN_REQUIRED = "Valid WhiteKey Admin Token required";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AdminTokenService tokenService) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/auth/**", "/api/auth/**", "/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex.authenticationEntryPoint((req, res, err) ->
                        res.sendError(HttpServletResponse.SC_UNAUTHORIZED, ERROR_ADMIN_TOKEN_REQUIRED)))
                .addFilterBefore((request, response, chain) -> {
                    HttpServletRequest req = (HttpServletRequest) request;
                    String auth = req.getHeader(HttpHeaders.AUTHORIZATION);
                    if (auth != null && auth.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
                        String token = auth.substring(BEARER_PREFIX.length()).strip();
                        if (tokenService.isValidToken(token)) {
                            SecurityContextHolder.getContext().setAuthentication(new PreAuthenticatedAuthenticationToken(ADMIN_PRINCIPAL, token, null));
                        }
                    }
                    chain.doFilter(request, response);
                }, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
