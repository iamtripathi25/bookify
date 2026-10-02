package com.iamtripathi25.bookify;

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
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ShowApiTest {

	@Autowired
	TestRestTemplate http;

	@Test
	void adminCreatesShowAndAnyUserReadsIt() {
		ResponseEntity<JsonNode> created = post("/shows", token("admin-1", "ADMIN"),
				Map.of("name", "friday-night", "seats", List.of("A1", "A2", "A10"), "price_paise", 25000));
		assertThat(created.getStatusCode().value()).isEqualTo(201);
		String id = created.getBody().get("id").asText();

		ResponseEntity<JsonNode> state = get("/shows/" + id, token("u-1", "USER"));
		assertThat(state.getStatusCode().value()).isEqualTo(200);
		JsonNode body = state.getBody();
		assertThat(body.get("total_seats").asInt()).isEqualTo(3);
		assertThat(body.get("per_user_limit").asInt()).isEqualTo(4);
		assertThat(body.at("/counts/available").asInt()).isEqualTo(3);
		assertThat(body.at("/counts/held").asInt()).isZero();
		assertThat(body.at("/counts/confirmed").asInt()).isZero();
		// Display order is the creation order, not lexical (A10 would sort before A2).
		assertThat(body.get("seats").findValuesAsText("label")).containsExactly("A1", "A2", "A10");
	}

	@Test
	void authErrorsAreJson() {
		Map<String, Object> show = Map.of("name", "x", "seats", List.of("A1"), "price_paise", 1);
		ResponseEntity<JsonNode> noToken = post("/shows", null, show);
		assertThat(noToken.getStatusCode().value()).isEqualTo(401);
		assertThat(noToken.getBody().get("code").asText()).isEqualTo("UNAUTHORIZED");

		ResponseEntity<JsonNode> userToken = post("/shows", token("u-1", "USER"), show);
		assertThat(userToken.getStatusCode().value()).isEqualTo(403);
		assertThat(userToken.getBody().get("code").asText()).isEqualTo("FORBIDDEN");

		ResponseEntity<JsonNode> badToken = get("/shows/00000000-0000-0000-0000-000000000000", "not-a-jwt");
		assertThat(badToken.getStatusCode().value()).isEqualTo(401);
	}

	@Test
	void invalidRequestsAre400Or404NotServerErrors() {
		String admin = token("admin-1", "ADMIN");
		assertError(post("/shows", admin, Map.of("name", "x", "seats", List.of(), "price_paise", 1)), 400,
				"INVALID_REQUEST");
		assertError(post("/shows", admin, Map.of("name", "x", "seats", List.of("A1", "A1"), "price_paise", 1)), 400,
				"INVALID_REQUEST");
		assertError(post("/shows", admin, Map.of("name", "x", "seats", List.of("A1"), "price_paise", -1)), 400,
				"INVALID_REQUEST");
		assertError(post("/shows", admin,
				Map.of("name", "x", "seats", List.of("A1"), "price_paise", 1, "per_user_limit", 0)), 400,
				"INVALID_REQUEST");
		assertError(postRaw("/shows", admin, "{not json"), 400, "INVALID_REQUEST");

		String user = token("u-1", "USER");
		assertError(get("/shows/not-a-uuid", user), 400, "INVALID_REQUEST");
		assertError(get("/shows/00000000-0000-0000-0000-000000000000", user), 404, "NOT_FOUND");
	}

	private void assertError(ResponseEntity<JsonNode> response, int status, String code) {
		assertThat(response.getStatusCode().value()).isEqualTo(status);
		assertThat(response.getBody().get("code").asText()).isEqualTo(code);
	}

	private String token(String userId, String role) {
		HttpHeaders headers = new HttpHeaders();
		headers.set("X-Admin-Key", "test-admin-key-0123456789");
		return http.postForObject("/auth/token", new HttpEntity<>(Map.of("user_id", userId, "role", role), headers),
				JsonNode.class)
			.get("token")
			.asText();
	}

	private ResponseEntity<JsonNode> get(String path, String token) {
		return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(token)), JsonNode.class);
	}

	private ResponseEntity<JsonNode> post(String path, String token, Object body) {
		return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers(token)), JsonNode.class);
	}

	private ResponseEntity<JsonNode> postRaw(String path, String token, String body) {
		HttpHeaders headers = headers(token);
		headers.set(HttpHeaders.CONTENT_TYPE, "application/json");
		return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
	}

	private static HttpHeaders headers(String token) {
		HttpHeaders headers = new HttpHeaders();
		if (token != null) {
			headers.setBearerAuth(token);
		}
		return headers;
	}

}
