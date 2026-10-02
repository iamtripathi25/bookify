package com.iamtripathi25.bookify;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/** Single-request reservation behaviour; races live in ConcurrencyIT. */
class ReservationApiTest extends ApiTestSupport {

	@Test
	void reservesAllRequestedSeats() {
		String show = createShow(List.of("A1", "A2", "A3"), 25000, 4);
		ResponseEntity<JsonNode> res = reserve(show, token("u-1"), List.of("A2", "A1"), newKey());

		assertThat(res.getStatusCode().value()).isEqualTo(201);
		JsonNode body = res.getBody();
		assertThat(body.get("reservation_id").asText()).isNotBlank();
		assertThat(body.get("show_id").asText()).isEqualTo(show);
		assertThat(body.get("user_id").asText()).isEqualTo("u-1");
		assertThat(body.get("seats").toString()).isEqualTo("[\"A1\",\"A2\"]");
		assertThat(body.get("amount_paise").asLong()).isEqualTo(50000);
		assertThat(body.get("status").asText()).isEqualTo("confirmed");
		assertThat(res.getHeaders().getLocation()).hasToString("/reservations/" + body.get("reservation_id").asText());

		JsonNode state = get("/shows/" + show, token("u-1")).getBody();
		assertThat(state.at("/counts/confirmed").asInt()).isEqualTo(2);
		assertThat(state.at("/counts/available").asInt()).isEqualTo(1);
		assertInvariants(show);
	}

	@Test
	void takenSeatIsA409ListingTheSeat() {
		String show = createShow(List.of("A1", "A2"), 100, 4);
		assertThat(reserve(show, token("u-1"), List.of("A1"), newKey()).getStatusCode().value()).isEqualTo(201);

		ResponseEntity<JsonNode> res = reserve(show, token("u-2"), List.of("A2", "A1"), newKey());
		assertError(res, 409, "SEAT_TAKEN");
		assertThat(res.getBody().get("seats").toString()).isEqualTo("[\"A1\"]");
		// All-or-nothing: A2 was free but must not have been taken.
		assertThat(get("/shows/" + show, token("u-2")).getBody().at("/counts/available").asInt()).isEqualTo(1);
		assertInvariants(show);
	}

	@Test
	void moreSeatsThanTheLimitInOneRequestIs409() {
		String show = createShow(List.of("A1", "A2", "A3"), 100, 2);
		assertError(reserve(show, token("u-1"), List.of("A1", "A2", "A3"), newKey()), 409, "PER_USER_LIMIT");
	}

	@Test
	void identityComesFromTheTokenNotTheBody() {
		String show = createShow(List.of("A1"), 100, 4);
		Map<String, Object> body = new HashMap<>();
		body.put("seats", List.of("A1"));
		body.put("idempotency_key", newKey());
		body.put("user_id", "victim");
		ResponseEntity<JsonNode> res = post("/shows/" + show + "/reserve", token("attacker"), body);
		assertThat(res.getStatusCode().value()).isEqualTo(201);
		assertThat(res.getBody().get("user_id").asText()).isEqualTo("attacker");
	}

	@Test
	void headerKeyWinsOverBodyKey() {
		String show = createShow(List.of("A1"), 100, 4);
		HttpHeaders headers = headers(token("u-1"));
		headers.set("Idempotency-Key", "from-header");
		ResponseEntity<JsonNode> res = post("/shows/" + show + "/reserve", headers,
				Map.of("seats", List.of("A1"), "idempotency_key", "from-body"));
		assertThat(res.getStatusCode().value()).isEqualTo(201);
		String stored = jdbc.queryForObject("SELECT idempotency_key FROM reservations WHERE id = ?::uuid",
				String.class, res.getBody().get("reservation_id").asText());
		assertThat(stored).isEqualTo("from-header");
	}

	@Test
	void retryWithTheSameKeyReplaysTheOriginal() {
		String show = createShow(List.of("A1", "A2", "A3"), 100, 4);
		String user = token("u-1");
		String key = newKey();
		ResponseEntity<JsonNode> first = reserve(show, user, List.of("A1", "A2"), key);
		assertThat(first.getStatusCode().value()).isEqualTo(201);

		// Same seats in a different order is the same request.
		ResponseEntity<JsonNode> retry = reserve(show, user, List.of("A2", "A1"), key);
		assertThat(retry.getStatusCode().value()).isEqualTo(200);
		assertThat(retry.getBody()).isEqualTo(first.getBody());

		JsonNode state = get("/shows/" + show, user).getBody();
		assertThat(state.at("/counts/confirmed").asInt()).isEqualTo(2);
		assertInvariants(show);
	}

	@Test
	void sameKeyWithDifferentSeatsIs409AndChangesNothing() {
		String show = createShow(List.of("A1", "A2"), 100, 4);
		String user = token("u-1");
		String key = newKey();
		assertThat(reserve(show, user, List.of("A1"), key).getStatusCode().value()).isEqualTo(201);

		assertError(reserve(show, user, List.of("A2"), key), 409, "IDEMPOTENCY_KEY_REUSED");
		assertError(reserve(show, user, List.of("A1", "A2"), key), 409, "IDEMPOTENCY_KEY_REUSED");
		assertThat(get("/shows/" + show, user).getBody().at("/counts/available").asInt()).isEqualTo(1);

		// The same key on another show is a different request too.
		String other = createShow(List.of("A1"), 100, 4);
		assertError(reserve(other, user, List.of("A1"), key), 409, "IDEMPOTENCY_KEY_REUSED");
		assertInvariants(show);
		assertInvariants(other);
	}

	@Test
	void keysAreScopedPerUser() {
		String show = createShow(List.of("A1", "A2"), 100, 4);
		String key = newKey();
		assertThat(reserve(show, token("u-1"), List.of("A1"), key).getStatusCode().value()).isEqualTo(201);
		assertThat(reserve(show, token("u-2"), List.of("A2"), key).getStatusCode().value()).isEqualTo(201);
	}

	@Test
	void aDeclinedAttemptLeavesNothingBehind() {
		String show = createShow(List.of("A1", "A2"), 100, 4);
		assertThat(reserve(show, token("u-1"), List.of("A1"), newKey()).getStatusCode().value()).isEqualTo(201);

		// u-2's first attempt loses A1. The key was not stored, so it can be used again for A2.
		String user = token("u-2");
		String key = newKey();
		assertError(reserve(show, user, List.of("A1"), key), 409, "SEAT_TAKEN");
		assertThat(reserve(show, user, List.of("A2"), key).getStatusCode().value()).isEqualTo(201);
		assertInvariants(show);
	}

	@Test
	void perUserLimitCountsAcrossReservations() {
		String show = createShow(List.of("A1", "A2", "A3", "A4"), 100, 3);
		String user = token("u-1");
		assertThat(reserve(show, user, List.of("A1", "A2"), newKey()).getStatusCode().value()).isEqualTo(201);
		assertError(reserve(show, user, List.of("A3", "A4"), newKey()), 409, "PER_USER_LIMIT");
		assertThat(reserve(show, user, List.of("A3"), newKey()).getStatusCode().value()).isEqualTo(201);
		assertError(reserve(show, user, List.of("A4"), newKey()), 409, "PER_USER_LIMIT");
		// Another user is unaffected.
		assertThat(reserve(show, token("u-2"), List.of("A4"), newKey()).getStatusCode().value()).isEqualTo(201);
		assertInvariants(show);
	}

	@Test
	void invalidReservationsAre4xx() {
		String show = createShow(List.of("A1"), 100, 4);
		String user = token("u-1");
		assertError(reserve(show, user, List.of("A1"), null), 400, "INVALID_REQUEST");
		assertError(reserve(show, user, List.of(), newKey()), 400, "INVALID_REQUEST");
		assertError(reserve(show, user, List.of("A1", "A1"), newKey()), 400, "INVALID_REQUEST");
		assertError(reserve(show, user, List.of("Z9"), newKey()), 400, "INVALID_REQUEST");
		assertError(reserve("00000000-0000-0000-0000-000000000000", user, List.of("A1"), newKey()), 404,
				"NOT_FOUND");
		assertError(reserve(show, null, List.of("A1"), newKey()), 401, "UNAUTHORIZED");
		assertInvariants(show);
	}

}
