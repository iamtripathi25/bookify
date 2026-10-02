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
