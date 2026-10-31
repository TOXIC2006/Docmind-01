package com.lcdw.springai.docmind01.Confi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lcdw.springai.docmind01.Execption.API_Respone;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    // Listed individually rather than "/docmind/auth/**" so that /docmind/auth/me,
    // which needs a signed-in principal, is not accidentally left open.
    private static final String[] PUBLIC_PATHS = {
            "/docmind/auth/register",
            "/docmind/auth/login",
            "/docmind/auth/verify/email",
            "/docmind/auth/resend",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/error"
    };  

    private final Appproperties appproperties;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // The JWT is the credential and no session is created, so there is no cookie to forge.
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) -> write(res, HttpStatus.UNAUTHORIZED,
                                "Authentication required. Sign in to continue."))
                        .accessDeniedHandler((req, res, e) -> write(res, HttpStatus.FORBIDDEN,
                                "You do not have access to this resource.")));

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    /**
     * Spring Security runs before the DispatcherServlet, so the WebMvcConfigurer mappings in
     * Crossorigin never see a preflight. Without this bean the browser 403s on every request again.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        Appproperties.CorsProperties cors = appproperties.getCors();

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(split(cors.getAllowedOrigins()));
        configuration.setAllowedMethods(split(cors.getAllowedMethods()));
        configuration.setAllowedHeaders(split(cors.getAllowedHeaders()));
        configuration.setMaxAge(1800L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private static List<String> split(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of("*");
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    // The SPA needs JSON it can parse, not Spring Security's default empty 401 body.
    private void write(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(
                new API_Respone(false, message, status.value(), LocalDateTime.now())));
    }
}
