package com.iamtripathi25.bookify;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AuthTokenTest {

	static final String ADMIN_KEY = "test-admin-key-0123456789";

	@Autowired
	TestRestTemplate http;

	/** Signs with the real key, so only the claims under test differ from a minted token. */
	@Autowired
	JwtEncoder encoder;

	@Test
	void userTokensAreOpen() {
		ResponseEntity<JsonNode> res = mint("u-1", null, null);
		assertThat(res.getStatusCode().value()).isEqualTo(200);
		assertThat(res.getBody().get("role").asText()).isEqualTo("USER");
	}

	@Test
	void adminTokensNeedTheAdminKey() {
		assertForbidden(mint("evil", "ADMIN", null));
		assertForbidden(mint("evil", "ADMIN", "wrong-key-wrong-key"));
		ResponseEntity<JsonNode> ok = mint("admin-1", "ADMIN", ADMIN_KEY);
		assertThat(ok.getStatusCode().value()).isEqualTo(200);
		assertThat(ok.getBody().get("role").asText()).isEqualTo("ADMIN");
	}

	@Test
	void mintedTokenIsAccepted() {
		String token = mint("u-1", null, null).getBody().get("token").asText();
		assertThat(getShow(token).getStatusCode().value()).isEqualTo(404);
	}

	@Test
	void tokensWithWrongIssuerAudienceOrExpiryAreRejected() {
		Instant now = Instant.now();
		assertThat(getShow(sign("someone-else", "bookify-api", now.plusSeconds(60))).getStatusCode().value())
			.isEqualTo(401);
		assertThat(getShow(sign("bookify", "other-app", now.plusSeconds(60))).getStatusCode().value())
			.isEqualTo(401);
		assertThat(getShow(sign("bookify", "bookify-api", now.minus(Duration.ofMinutes(5)))).getStatusCode().value())
			.isEqualTo(401);
		// Control: the same helper with correct claims is accepted (404 = authenticated, show missing).
		assertThat(getShow(sign("bookify", "bookify-api", now.plusSeconds(60))).getStatusCode().value())
			.isEqualTo(404);
	}

	private String sign(String issuer, String audience, Instant expiresAt) {
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.issuer(issuer)
			.audience(List.of(audience))
			.subject("u-1")
			.issuedAt(expiresAt.minus(Duration.ofHours(1)))
			.expiresAt(expiresAt)
			.claim("roles", List.of("USER"))
			.build();
		return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
			.getTokenValue();
	}

	private ResponseEntity<JsonNode> mint(String userId, String role, String adminKey) {
		HttpHeaders headers = new HttpHeaders();
		if (adminKey != null) {
			headers.set("X-Admin-Key", adminKey);
		}
		Map<String, String> body = role == null ? Map.of("user_id", userId) : Map.of("user_id", userId, "role", role);
		return http.exchange("/auth/token", HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
	}

	private ResponseEntity<JsonNode> getShow(String token) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(token);
		return http.exchange("/shows/00000000-0000-0000-0000-000000000000", HttpMethod.GET,
				new HttpEntity<>(headers), JsonNode.class);
	}

	private static void assertForbidden(ResponseEntity<JsonNode> res) {
		assertThat(res.getStatusCode().value()).isEqualTo(403);
		assertThat(res.getBody().get("code").asText()).isEqualTo("FORBIDDEN");
	}

}
