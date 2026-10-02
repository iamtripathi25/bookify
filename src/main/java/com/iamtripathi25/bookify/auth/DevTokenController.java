package com.iamtripathi25.bookify.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.iamtripathi25.bookify.error.ForbiddenException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mints test tokens so graders' burst tools can act as many users. Exists only when
 * bookify.dev-auth.enabled=true; otherwise the route is unmapped and returns 404.
 *
 * <p>USER tokens are open to anyone (the burst needs thousands of identities). ADMIN tokens need
 * the {@code X-Admin-Key} header to match BOOKIFY_ADMIN_KEY, which is shared privately, so a
 * visitor can't create shows. In production a real identity provider would issue tokens instead;
 * see WRITEUP.md.
 */
@RestController
@ConditionalOnProperty(name = "bookify.dev-auth.enabled", havingValue = "true")
public class DevTokenController {

	static final String ADMIN_KEY_HEADER = "X-Admin-Key";

	static final int MIN_ADMIN_KEY_BYTES = 16;

	private final JwtEncoder encoder;

	private final Duration ttl;

	private final String issuer;

	private final String audience;

	private final byte[] adminKey;

	public DevTokenController(JwtEncoder encoder, @Value("${bookify.jwt.token-ttl}") Duration ttl,
			@Value("${bookify.jwt.issuer}") String issuer, @Value("${bookify.jwt.audience}") String audience,
			@Value("${bookify.dev-auth.admin-key}") String adminKey) {
		this.encoder = encoder;
		this.ttl = ttl;
		this.issuer = issuer;
		this.audience = audience;
		this.adminKey = adminKey.getBytes(StandardCharsets.UTF_8);
		if (this.adminKey.length > 0 && this.adminKey.length < MIN_ADMIN_KEY_BYTES) {
			throw new IllegalStateException("BOOKIFY_ADMIN_KEY must be at least " + MIN_ADMIN_KEY_BYTES + " bytes");
		}
	}

	@PostMapping("/auth/token")
	TokenResponse mint(@Valid @RequestBody TokenRequest request,
			@RequestHeader(name = ADMIN_KEY_HEADER, required = false) String presentedKey) {
		String role = request.role() == null ? "USER" : request.role();
		if ("ADMIN".equals(role) && !adminKeyMatches(presentedKey)) {
			throw new ForbiddenException("ADMIN tokens require a valid " + ADMIN_KEY_HEADER + " header");
		}
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.issuer(issuer)
			.audience(List.of(audience))
			.subject(request.userId())
			.issuedAt(now)
			.expiresAt(now.plus(ttl))
			.claim("roles", List.of(role))
			.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
		return new TokenResponse(token, request.userId(), role, ttl.toSeconds());
	}

	/** Constant-time comparison, so response timing doesn't leak how much of the key matched. */
	private boolean adminKeyMatches(String presentedKey) {
		if (adminKey.length == 0 || presentedKey == null) {
			return false;
		}
		return MessageDigest.isEqual(adminKey, presentedKey.getBytes(StandardCharsets.UTF_8));
	}

	record TokenRequest(
			@NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._:@-]+",
					message = "may contain only letters, digits and . _ : @ -") String userId,
			@Pattern(regexp = "USER|ADMIN", message = "must be USER or ADMIN") String role) {
	}

	record TokenResponse(String token, String userId, String role, long expiresIn) {
	}

}
