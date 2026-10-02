package com.iamtripathi25.bookify;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.iamtripathi25.bookify.config.SeatGauges;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityTest extends ApiTestSupport {

	@Autowired
	MeterRegistry registry;

	@Autowired
	SeatGauges seatGauges;

	@Test
	void requestIdIsEchoedOrGenerated() {
		HttpHeaders headers = new HttpHeaders();
		headers.set("X-Request-Id", "trace-123");
		ResponseEntity<JsonNode> supplied = http.exchange("/shows/00000000-0000-0000-0000-000000000000",
				HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
		assertThat(supplied.getHeaders().getFirst("X-Request-Id")).isEqualTo("trace-123");
		// Even a 401 from the security chain carries the id, in the header and the body.
		assertThat(supplied.getStatusCode().value()).isEqualTo(401);
		assertThat(supplied.getBody().get("request_id").asText()).isEqualTo("trace-123");

		ResponseEntity<JsonNode> generated = get("/shows/not-a-uuid", token("u-1"));
		String id = generated.getHeaders().getFirst("X-Request-Id");
		assertThat(id).matches("[0-9a-f-]{36}");
		assertThat(generated.getBody().get("request_id").asText()).isEqualTo(id);
	}

	@Test
	void unsafeRequestIdsAreReplaced() {
		HttpHeaders headers = new HttpHeaders();
		headers.set("X-Request-Id", "bad id with spaces and \"quotes\"");
		ResponseEntity<JsonNode> res = http.exchange("/shows/x", HttpMethod.GET, new HttpEntity<>(headers),
				JsonNode.class);
		assertThat(res.getHeaders().getFirst("X-Request-Id")).matches("[0-9a-f-]{36}");
	}

	@Test
	void countersTrackEachOutcome() {
		String show = createShow(List.of("A1", "A2", "A3"), 100, 2);
		String u1 = token("u-1");
		String key = newKey();

		String id = reserve(show, u1, List.of("A1", "A2"), key).getBody().get("reservation_id").asText();
		assertThat(count("bookify.reservations.confirmed", show)).isEqualTo(1);
		assertThat(count("bookify.seats.confirmed", show)).isEqualTo(2);

		reserve(show, u1, List.of("A1", "A2"), key);
		assertThat(count("bookify.reservations.replayed", show)).isEqualTo(1);
		assertThat(declined(show, "idempotent_replay")).isEqualTo(1);

		reserve(show, u1, List.of("A3"), key);
		assertThat(declined(show, "idempotency_mismatch")).isEqualTo(1);

		reserve(show, u1, List.of("A3"), newKey());
		assertThat(declined(show, "per_user_limit")).isEqualTo(1);

		reserve(show, token("u-2"), List.of("A1"), newKey());
		assertThat(declined(show, "seat_taken")).isEqualTo(1);

		// Invalid requests are not declines; every reason is registered from show creation.
		reserve(show, u1, List.of("Z9"), newKey());
		assertThat(registry.find("bookify.reservations.declined").tag("show", show).counters()).hasSize(5)
			.allMatch(c -> c.count() == (c.getId().getTag("reason").equals("contention") ? 0 : 1));

		cancel(id, u1);
		cancel(id, u1);
		assertThat(count("bookify.reservations.cancelled", show)).as("repeat cancel doesn't count").isEqualTo(1);
		assertThat(count("bookify.seats.released", show)).isEqualTo(2);
		// Nothing was confirmed twice along the way.
		assertThat(count("bookify.reservations.confirmed", show)).isEqualTo(1);
	}

	@Test
	void seatGaugesMatchTheApi() {
		String show = createShow(List.of("A1", "A2", "A3", "A4"), 100, 4);
		reserve(show, token("u-1"), List.of("A1", "A2"), newKey());
		seatGauges.refresh();

		JsonNode counts = get("/shows/" + show, token("u-1")).getBody().get("counts");
		for (String status : List.of("available", "held", "confirmed")) {
			Gauge gauge = registry.find("bookify.seats").tags("show", show, "status", status).gauge();
			assertThat(gauge).as(status).isNotNull();
			assertThat(gauge.value()).as(status).isEqualTo(counts.get(status).asDouble());
		}
		assertThat(registry.find("bookify.show.seats").tag("show", show).gauge().value()).isEqualTo(4);
	}

	private double count(String name, String show) {
		Counter counter = registry.find(name).tag("show", show).counter();
		return counter == null ? 0 : counter.count();
	}

	private double declined(String show, String reason) {
		Counter counter = registry.find("bookify.reservations.declined").tags("show", show, "reason", reason).counter();
		return counter == null ? 0 : counter.count();
	}

}
