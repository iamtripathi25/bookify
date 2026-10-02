package com.iamtripathi25.bookify;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class ShowApiTest extends ApiTestSupport {

	@Test
	void adminCreatesShowAndAnyUserReadsIt() {
		ResponseEntity<JsonNode> created = post("/shows", token("admin-1", "ADMIN"),
				Map.of("name", "friday-night", "seats", List.of("A1", "A2", "A10"), "price_paise", 25000));
		assertThat(created.getStatusCode().value()).isEqualTo(201);
		String id = created.getBody().get("id").asText();

		ResponseEntity<JsonNode> state = get("/shows/" + id, token("u-1"));
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
		assertError(post("/shows", (String) null, show), 401, "UNAUTHORIZED");
		assertError(post("/shows", token("u-1"), show), 403, "FORBIDDEN");
		assertError(get("/shows/00000000-0000-0000-0000-000000000000", "not-a-jwt"), 401, "UNAUTHORIZED");
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
		HttpHeaders json = headers(admin);
		json.set(HttpHeaders.CONTENT_TYPE, "application/json");
		assertError(post("/shows", json, "{not json"), 400, "INVALID_REQUEST");

		String user = token("u-1");
		assertError(get("/shows/not-a-uuid", user), 400, "INVALID_REQUEST");
		assertError(get("/shows/00000000-0000-0000-0000-000000000000", user), 404, "NOT_FOUND");
	}

}
