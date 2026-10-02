package com.iamtripathi25.bookify.config;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iamtripathi25.bookify.error.ErrorBody;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

@Configuration
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
		AuthenticationEntryPoint unauthorized = (req, res, ex) -> writeError(objectMapper, res,
				HttpServletResponse.SC_UNAUTHORIZED, ErrorBody.of("UNAUTHORIZED", "Missing or invalid bearer token"));
		AccessDeniedHandler forbidden = (req, res, ex) -> writeError(objectMapper, res,
				HttpServletResponse.SC_FORBIDDEN, ErrorBody.of("FORBIDDEN", "Insufficient role"));

		return http
			.csrf(csrf -> csrf.disable())
			.httpBasic(basic -> basic.disable())
			.formLogin(form -> form.disable())
			.logout(logout -> logout.disable())
			.requestCache(cache -> cache.disable())
			.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
				// Returns 404 unless bookify.dev-auth.enabled=true.
				.requestMatchers(HttpMethod.POST, "/auth/token").permitAll()
				.requestMatchers("/error").permitAll()
				.requestMatchers(HttpMethod.POST, "/shows").hasRole("ADMIN")
				.anyRequest().authenticated())
			.oauth2ResourceServer(oauth -> oauth
				.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
				.authenticationEntryPoint(unauthorized)
				.accessDeniedHandler(forbidden))
			.exceptionHandling(ex -> ex.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
			.addFilterAfter(new UserLogContextFilter(), BearerTokenAuthenticationFilter.class)
			.build();
	}

	/** Maps the {@code roles} claim (["USER"] / ["ADMIN"]) to ROLE_* authorities. */
	private static JwtAuthenticationConverter jwtAuthenticationConverter() {
		JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
		roles.setAuthoritiesClaimName("roles");
		roles.setAuthorityPrefix("ROLE_");
		JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(roles);
		return converter;
	}

	private static void writeError(ObjectMapper objectMapper, HttpServletResponse res, int status, ErrorBody body)
			throws IOException {
		LogContext.outcomeIfAbsent(body.code().toLowerCase());
		res.setStatus(status);
		res.setContentType(MediaType.APPLICATION_JSON_VALUE);
		objectMapper.writeValue(res.getOutputStream(), body);
	}

}
