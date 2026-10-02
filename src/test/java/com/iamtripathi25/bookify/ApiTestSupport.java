package com.iamtripathi25.bookify;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP against the full app (security, validation, error mapping) on Testcontainers Postgres. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class ApiTestSupport {

	protected static final String ADMIN_KEY = "test-admin-key-0123456789";

	@Autowired
	protected TestRestTemplate http;

	@Autowired
	protected JdbcTemplate jdbc;

	protected String token(String userId) {
		return token(userId, "USER");
	}

	protected String token(String userId, String role) {
		HttpHeaders headers = new HttpHeaders();
		headers.set("X-Admin-Key", ADMIN_KEY);
		return http.postForObject("/auth/token", new HttpEntity<>(Map.of("user_id", userId, "role", role), headers),
				JsonNode.class)
			.get("token")
			.asText();
	}

	/** Creates a show through the API and returns its id. */
	protected String createShow(List<String> seats, long pricePaise, int perUserLimit) {
		Map<String, Object> body = Map.of("name", "test-show", "seats", seats, "price_paise", pricePaise,
				"per_user_limit", perUserLimit);
		ResponseEntity<JsonNode> res = post("/shows", token("admin", "ADMIN"), body);
		assertThat(res.getStatusCode().value()).isEqualTo(201);
		return res.getBody().get("id").asText();
	}

	protected ResponseEntity<JsonNode> reserve(String showId, String token, List<String> seats, String key) {
		Map<String, Object> body = new HashMap<>();
		body.put("seats", seats);
		body.put("idempotency_key", key);
		return post("/shows/" + showId + "/reserve", token, body);
	}

	protected static String newKey() {
		return UUID.randomUUID().toString();
	}

	protected ResponseEntity<JsonNode> get(String path, String token) {
		return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(token)), JsonNode.class);
	}

	protected ResponseEntity<JsonNode> post(String path, String token, Object body) {
		return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers(token)), JsonNode.class);
	}

	protected ResponseEntity<JsonNode> post(String path, HttpHeaders headers, Object body) {
		return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
	}

	protected static HttpHeaders headers(String token) {
		HttpHeaders headers = new HttpHeaders();
		if (token != null) {
			headers.setBearerAuth(token);
		}
		return headers;
	}

	protected static void assertError(ResponseEntity<JsonNode> response, int status, String code) {
		assertThat(response.getStatusCode().value()).as("status, body=%s", response.getBody()).isEqualTo(status);
		assertThat(response.getBody().get("code").asText()).isEqualTo(code);
	}

	/**
	 * The invariants every test must leave intact: per-seat states sum to the total, an owned seat
	 * belongs to a confirmed reservation of the same user, a confirmed reservation owns exactly its
	 * seats, and each user's quota count equals the seats they own.
	 */
	protected void assertInvariants(String showId) {
		UUID id = UUID.fromString(showId);
		Map<String, Object> counts = jdbc.queryForMap("""
				SELECT s.total_seats,
				       count(*) FILTER (WHERE st.status = 'available') AS available,
				       count(*) FILTER (WHERE st.status = 'held')      AS held,
				       count(*) FILTER (WHERE st.status = 'confirmed') AS confirmed
				FROM shows s JOIN seats st ON st.show_id = s.id
				WHERE s.id = ? GROUP BY s.total_seats
				""", id);
		long total = ((Number) counts.get("total_seats")).longValue();
		long sum = ((Number) counts.get("available")).longValue() + ((Number) counts.get("held")).longValue()
				+ ((Number) counts.get("confirmed")).longValue();
		assertThat(sum).as("available + held + confirmed == total_seats").isEqualTo(total);

		Integer orphaned = jdbc.queryForObject("""
				SELECT count(*) FROM seats st
				LEFT JOIN reservations r ON r.id = st.reservation_id
				WHERE st.show_id = ? AND st.reservation_id IS NOT NULL
				  AND (r.id IS NULL OR r.status <> 'confirmed' OR r.user_id <> st.user_id)
				""", Integer.class, id);
		assertThat(orphaned).as("owned seats without a matching confirmed reservation").isZero();

		Integer mismatched = jdbc.queryForObject("""
				SELECT count(*) FROM reservations r
				WHERE r.show_id = ? AND r.status = 'confirmed'
				  AND cardinality(r.seats) <> (SELECT count(*) FROM seats st WHERE st.reservation_id = r.id)
				""", Integer.class, id);
		assertThat(mismatched).as("confirmed reservations not owning exactly their seats").isZero();

		Integer quotaDrift = jdbc.queryForObject("""
				SELECT count(*) FROM (
				  SELECT u.user_id, u.held,
				         (SELECT count(*) FROM seats st WHERE st.show_id = u.show_id AND st.user_id = u.user_id) AS owned
				  FROM user_show_counts u WHERE u.show_id = ?
				) q WHERE q.held <> q.owned
				""", Integer.class, id);
		assertThat(quotaDrift).as("users whose quota count differs from the seats they own").isZero();
		Integer uncounted = jdbc.queryForObject("""
				SELECT count(*) FROM seats st
				WHERE st.show_id = ? AND st.user_id IS NOT NULL
				  AND NOT EXISTS (SELECT 1 FROM user_show_counts u WHERE u.show_id = st.show_id AND u.user_id = st.user_id)
				""", Integer.class, id);
		assertThat(uncounted).as("owned seats missing from the quota table").isZero();
	}

}
