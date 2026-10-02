package com.iamtripathi25.bookify;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.iamtripathi25.bookify.error.SeatTakenException;
import com.iamtripathi25.bookify.reservation.Reservation;
import com.iamtripathi25.bookify.reservation.ReservationTxService;
import com.iamtripathi25.bookify.reservation.ReserveResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Races against real Postgres over real HTTP. Every request in a test waits on one latch so they
 * fire together.
 */
class ConcurrencyIT extends ApiTestSupport {

	@Autowired
	ReservationTxService tx;

	@Test
	void hotSeatHasExactlyOneWinner() throws Exception {
		String show = createShow(List.of("A12", "A13"), 25000, 4);
		int racers = 500;
		List<String> tokens = IntStream.range(0, racers).mapToObj(i -> token("hot-" + i)).toList();

		List<ResponseEntity<JsonNode>> results = race(racers,
				i -> reserve(show, tokens.get(i), List.of("A12"), newKey()));

		assertThat(statuses(results)).doesNotContainAnyElementsOf(List.of(500, 502, 503, 504));
		List<ResponseEntity<JsonNode>> winners = results.stream()
			.filter(r -> r.getStatusCode().value() == 201)
			.toList();
		assertThat(winners).hasSize(1);
		results.stream()
			.filter(r -> r.getStatusCode().value() != 201)
			.forEach(r -> assertError(r, 409, "SEAT_TAKEN"));

		String winner = winners.get(0).getBody().get("user_id").asText();
		String owner = jdbc.queryForObject("SELECT user_id FROM seats WHERE show_id = ?::uuid AND label = 'A12'",
				String.class, show);
		assertThat(owner).isEqualTo(winner);
		assertInvariants(show);
	}

	@Test
	void opposingMultiSeatRequestsDoNotDeadlock() throws Exception {
		String show = createShow(List.of("A1", "A2"), 100, 4);
		int pairs = 200;
		List<String> tokens = IntStream.range(0, pairs * 2).mapToObj(i -> token("pair-" + i)).toList();

		// Half ask for [A1, A2], half for [A2, A1].
		List<ResponseEntity<JsonNode>> results = race(pairs * 2, i -> reserve(show, tokens.get(i),
				i % 2 == 0 ? List.of("A1", "A2") : List.of("A2", "A1"), newKey()));

		// Every loser is a clean SEAT_TAKEN: no deadlock surfaced as CONTENTION or a 5xx.
		assertThat(results.stream().filter(r -> r.getStatusCode().value() == 201)).hasSize(1);
		results.stream()
			.filter(r -> r.getStatusCode().value() != 201)
			.forEach(r -> assertError(r, 409, "SEAT_TAKEN"));
		assertInvariants(show);
	}

	@Test
	void identityIsTokenDerivedUnderConcurrency() throws Exception {
		String show = createShow(List.of("V1", "S1", "S2", "S3", "S4", "S5"), 100, 4);
		String victim = token("victim");
		String victimReservation = reserve(show, victim, List.of("V1"), newKey()).getBody()
			.get("reservation_id")
			.asText();
		List<String> attackers = IntStream.range(0, 5).mapToObj(i -> token("attacker-" + i)).toList();

		// 5 attackers each send a reserve with a spoofed body user_id, and 5 more try to cancel and
		// read the victim's reservation, all at once.
		List<ResponseEntity<JsonNode>> results = race(15, i -> {
			String attacker = attackers.get(i % 5);
			if (i < 5) {
				Map<String, Object> body = new HashMap<>();
				body.put("seats", List.of("S" + (i + 1)));
				body.put("idempotency_key", newKey());
				body.put("user_id", "victim");
				return post("/shows/" + show + "/reserve", attacker, body);
			}
			return i < 10 ? cancel(victimReservation, attacker) : get("/reservations/" + victimReservation, attacker);
		});

		for (int i = 0; i < 5; i++) {
			assertThat(results.get(i).getStatusCode().value()).isEqualTo(201);
			assertThat(results.get(i).getBody().get("user_id").asText()).isEqualTo("attacker-" + i);
		}
		results.subList(5, 15).forEach(r -> assertError(r, 404, "NOT_FOUND"));
		assertThat(get("/reservations/" + victimReservation, victim).getBody().get("status").asText())
			.isEqualTo("confirmed");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM seats WHERE show_id = ?::uuid AND user_id = 'victim'",
				Integer.class, show)).isEqualTo(1);
		assertInvariants(show);
	}

	/**
	 * A small version of the on-sale stampede: many users, a few hot seats, same-key retries and key
	 * reuse mixed together. The API's final state must reconcile exactly with the responses.
	 */
	@Test
	void mixedStampedeReconcilesWithResponses() throws Exception {
		List<String> seats = IntStream.rangeClosed(1, 100).mapToObj(i -> "P" + i).toList();
		String show = createShow(seats, 25000, 4);
		int users = 200;
		List<String> tokens = IntStream.range(0, users).mapToObj(i -> token("mix-" + i)).toList();
		List<String> keys = IntStream.range(0, users).mapToObj(i -> newKey()).toList();
		Random random = new Random(42);
		// Each user wants one seat: 80% aim at 5 hot seats, the rest anywhere.
		List<String> wanted = IntStream.range(0, users)
			.mapToObj(i -> random.nextInt(10) < 8 ? seats.get(random.nextInt(5)) : seats.get(random.nextInt(100)))
			.toList();

		// 1000 requests: each user's request is sent several times with its key (retries), and every
		// 25th request reuses the key for a different seat.
		int requests = 1000;
		List<ResponseEntity<JsonNode>> results = race(requests, i -> {
			int u = i % users;
			String seat = i % 25 == 24 ? seats.get((seats.indexOf(wanted.get(u)) + 1) % 100) : wanted.get(u);
			return reserve(show, tokens.get(u), List.of(seat), keys.get(u));
		});

		assertThat(statuses(results)).allMatch(s -> s == 200 || s == 201 || s == 409, "only 200, 201 or 409");
		results.stream()
			.filter(r -> r.getStatusCode().value() == 409)
			.forEach(r -> assertThat(r.getBody().get("code").asText()).isIn("SEAT_TAKEN", "IDEMPOTENCY_KEY_REUSED"));

		// Each 201 is one booked seat, and no seat is confirmed twice.
		List<String> bookedSeats = results.stream()
			.filter(r -> r.getStatusCode().value() == 201)
			.map(r -> r.getBody().get("seats").get(0).asText())
			.toList();
		assertThat(bookedSeats).doesNotHaveDuplicates();
		JsonNode state = get("/shows/" + show, tokens.get(0)).getBody();
		assertThat(state.at("/counts/confirmed").asInt()).isEqualTo(bookedSeats.size());
		// Every 200 replays a reservation that some 201 created.
		Set<String> created = results.stream()
			.filter(r -> r.getStatusCode().value() == 201)
			.map(r -> r.getBody().get("reservation_id").asText())
			.collect(Collectors.toSet());
		results.stream()
			.filter(r -> r.getStatusCode().value() == 200)
			.forEach(r -> assertThat(created).contains(r.getBody().get("reservation_id").asText()));
		assertInvariants(show);
	}

	/** Every show any test touched, not just the one a test asserted on. */
	@AfterEach
	void invariantsHoldForAllShows() {
		jdbc.queryForList("SELECT id::text FROM shows", String.class).forEach(this::assertInvariants);
	}

	@Test
	void idempotencyStormBooksOnce() throws Exception {
		String show = createShow(List.of("A1", "A2"), 25000, 4);
		String user = token("storm-user");
		String key = newKey();

		List<ResponseEntity<JsonNode>> results = race(20, i -> reserve(show, user, List.of("A1"), key));

		assertThat(results.stream().filter(r -> r.getStatusCode().value() == 201)).hasSize(1);
		assertThat(results.stream().filter(r -> r.getStatusCode().value() == 200)).hasSize(19);
		assertThat(results.stream().map(r -> r.getBody().get("reservation_id").asText()).distinct()).hasSize(1);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM reservations WHERE user_id = 'storm-user'",
				Integer.class)).isEqualTo(1);
		assertInvariants(show);
	}

	@Test
	void keyReuseStormWithDifferentSeatsBooksOnce() throws Exception {
		String show = createShow(List.of("A1", "A2"), 25000, 4);
		String user = token("reuse-user");
		String key = newKey();

		// Half the requests want A1, half want A2, all under one key.
		List<ResponseEntity<JsonNode>> results = race(20,
				i -> reserve(show, user, List.of(i % 2 == 0 ? "A1" : "A2"), key));

		List<ResponseEntity<JsonNode>> created = results.stream()
			.filter(r -> r.getStatusCode().value() == 201)
			.toList();
		assertThat(created).hasSize(1);
		JsonNode winner = created.get(0).getBody();
		for (ResponseEntity<JsonNode> r : results) {
			int status = r.getStatusCode().value();
			if (status == 200) {
				// Only the winner's own body can replay.
				assertThat(r.getBody().get("reservation_id").asText()).isEqualTo(winner.get("reservation_id").asText());
			}
			else if (status != 201) {
				assertError(r, 409, "IDEMPOTENCY_KEY_REUSED");
			}
		}
		JsonNode state = get("/shows/" + show, user).getBody();
		assertThat(state.at("/counts/confirmed").asInt()).isEqualTo(1);
		assertInvariants(show);
	}

	@Test
	void perUserLimitHoldsUnderConcurrency() throws Exception {
		List<String> seats = IntStream.rangeClosed(1, 10).mapToObj(i -> "L" + i).toList();
		String show = createShow(seats, 100, 4);
		String user = token("limit-user");

		List<ResponseEntity<JsonNode>> results = race(10,
				i -> reserve(show, user, List.of(seats.get(i)), newKey()));

		assertThat(results.stream().filter(r -> r.getStatusCode().value() == 201)).hasSize(4);
		results.stream()
			.filter(r -> r.getStatusCode().value() != 201)
			.forEach(r -> assertError(r, 409, "PER_USER_LIMIT"));
		Integer owned = jdbc.queryForObject(
				"SELECT count(*) FROM seats WHERE show_id = ?::uuid AND user_id = 'limit-user'", Integer.class, show);
		Integer held = jdbc.queryForObject(
				"SELECT held FROM user_show_counts WHERE show_id = ?::uuid AND user_id = 'limit-user'", Integer.class,
				show);
		assertThat(owned).isEqualTo(4);
		assertThat(held).isEqualTo(4);
		assertInvariants(show);
	}

	@Test
	void multiSeatRequestsRespectTheLimitUnderConcurrency() throws Exception {
		List<String> seats = IntStream.rangeClosed(1, 12).mapToObj(i -> "M" + i).toList();
		String show = createShow(seats, 100, 4);
		String user = token("multi-limit-user");

		// Six parallel 2-seat requests against a limit of 4: exactly two can succeed.
		List<ResponseEntity<JsonNode>> results = race(6,
				i -> reserve(show, user, seats.subList(i * 2, i * 2 + 2), newKey()));

		assertThat(results.stream().filter(r -> r.getStatusCode().value() == 201)).hasSize(2);
		results.stream()
			.filter(r -> r.getStatusCode().value() != 201)
			.forEach(r -> assertError(r, 409, "PER_USER_LIMIT"));
		assertInvariants(show);
	}

	@Test
	void freedSeatHasExactlyOneNewWinner() throws Exception {
		String show = createShow(List.of("A1"), 100, 4);
		String owner = token("first-owner");
		String key = newKey();
		String original = reserve(show, owner, List.of("A1"), key).getBody().get("reservation_id").asText();
		assertThat(cancel(original, owner).getStatusCode().value()).isEqualTo(200);

		List<String> tokens = IntStream.range(0, 50).mapToObj(i -> token("rebook-" + i)).toList();
		List<ResponseEntity<JsonNode>> results = race(50, i -> reserve(show, tokens.get(i), List.of("A1"), newKey()));

		assertThat(results.stream().filter(r -> r.getStatusCode().value() == 201)).hasSize(1);
		results.stream()
			.filter(r -> r.getStatusCode().value() != 201)
			.forEach(r -> assertError(r, 409, "SEAT_TAKEN"));
		assertThat(get("/reservations/" + original, owner).getBody().get("status").asText()).isEqualTo("cancelled");
		assertInvariants(show);
	}

	@Test
	void cancelRacingRebookersNeverDoubleSells() throws Exception {
		String show = createShow(List.of("A1"), 100, 4);
		String owner = token("racing-owner");
		String original = reserve(show, owner, List.of("A1"), newKey()).getBody().get("reservation_id").asText();
		List<String> tokens = IntStream.range(0, 50).mapToObj(i -> token("racer-" + i)).toList();

		// Request 0 cancels; the other 50 try to grab A1 at the same moment.
		List<ResponseEntity<JsonNode>> results = race(51,
				i -> i == 0 ? cancel(original, owner) : reserve(show, tokens.get(i - 1), List.of("A1"), newKey()));

		assertThat(results.get(0).getStatusCode().value()).isEqualTo(200);
		List<ResponseEntity<JsonNode>> rebooks = results.subList(1, results.size());
		assertThat(rebooks.stream().filter(r -> r.getStatusCode().value() == 201).count()).isLessThanOrEqualTo(1);
		rebooks.stream()
			.filter(r -> r.getStatusCode().value() != 201)
			.forEach(r -> assertError(r, 409, "SEAT_TAKEN"));
		assertInvariants(show);
	}

	@Test
	void concurrentCancelsAndRetriesMoveNothingExtra() throws Exception {
		String show = createShow(List.of("A1", "A2"), 100, 4);
		String owner = token("double-cancel");
		String key = newKey();
		String id = reserve(show, owner, List.of("A1", "A2"), key).getBody().get("reservation_id").asText();

		// Ten cancels and ten same-key retries, all at once.
		List<ResponseEntity<JsonNode>> results = race(20,
				i -> i % 2 == 0 ? cancel(id, owner) : reserve(show, owner, List.of("A1", "A2"), key));

		for (ResponseEntity<JsonNode> r : results) {
			assertThat(r.getStatusCode().value()).as("body=%s", r.getBody()).isEqualTo(200);
			assertThat(r.getBody().get("reservation_id").asText()).isEqualTo(id);
		}
		assertThat(get("/reservations/" + id, owner).getBody().get("status").asText()).isEqualTo("cancelled");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM reservations WHERE user_id = 'double-cancel'",
				Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject(
				"SELECT held FROM user_show_counts WHERE show_id = ?::uuid AND user_id = 'double-cancel'", Integer.class,
				show)).isZero();
		assertThat(get("/shows/" + show, owner).getBody().at("/counts/available").asInt()).isEqualTo(2);
		assertInvariants(show);
	}

	/**
	 * The HTTP races above are mostly decided by the lock-free pre-check. These call the write
	 * transaction directly, so every racer reaches the row locks and the guarded update.
	 */
	@Test
	void hotSeatAtTheLockLayerHasExactlyOneWinner() throws Exception {
		String show = createShow(List.of("A12"), 100, 4);
		UUID showId = UUID.fromString(show);
		int racers = 200;

		List<Object> outcomes = raceTx(racers, i -> tx.reserve(
				new Reservation(UUID.randomUUID(), showId, "lock-" + i, List.of("A12"), 100, Reservation.CONFIRMED),
				newKey(), "hash", 4));

		assertThat(outcomes.stream().filter(ReserveResult.class::isInstance)).hasSize(1);
		assertThat(outcomes.stream().filter(SeatTakenException.class::isInstance)).hasSize(racers - 1);
		assertInvariants(show);
	}

	@Test
	void opposingLockOrdersAtTheLockLayerNeverDeadlock() throws Exception {
		List<String> seats = List.of("B1", "B2", "B3", "B4");
		String show = createShow(seats, 100, 4);
		UUID showId = UUID.fromString(show);
		int racers = 200;

		// Every racer asks for all four seats in a different order; Postgres must still lock B1 first.
		List<Object> outcomes = raceTx(racers, i -> {
			List<String> mine = new ArrayList<>(seats);
			Collections.rotate(mine, i);
			return tx.reserve(
					new Reservation(UUID.randomUUID(), showId, "order-" + i, mine, 400, Reservation.CONFIRMED),
					newKey(), "hash", 4);
		});

		assertThat(outcomes).allMatch(o -> o instanceof ReserveResult || o instanceof SeatTakenException,
				"only a win or SEAT_TAKEN, never a deadlock");
		assertThat(outcomes.stream().filter(ReserveResult.class::isInstance)).hasSize(1);
		assertInvariants(show);
	}

	/** Like {@link #race} but against a bean; returns each call's result or the exception it threw. */
	static List<Object> raceTx(int n, IntFunction<Object> call) throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Future<Object>> futures = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				int index = i;
				futures.add(pool.submit(() -> {
					start.await();
					try {
						return call.apply(index);
					}
					catch (RuntimeException ex) {
						return ex;
					}
				}));
			}
			start.countDown();
			List<Object> results = new ArrayList<>();
			for (Future<Object> f : futures) {
				results.add(f.get());
			}
			return results;
		}
	}

	interface Call {

		ResponseEntity<JsonNode> run(int index) throws Exception;

	}

	/** Runs {@code n} calls on virtual threads, all released at the same instant. */
	static List<ResponseEntity<JsonNode>> race(int n, Call call) throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Future<ResponseEntity<JsonNode>>> futures = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				int index = i;
				Callable<ResponseEntity<JsonNode>> task = () -> {
					start.await();
					return call.run(index);
				};
				futures.add(pool.submit(task));
			}
			start.countDown();
			List<ResponseEntity<JsonNode>> results = new ArrayList<>();
			for (Future<ResponseEntity<JsonNode>> f : futures) {
				results.add(f.get());
			}
			return results;
		}
	}

	static List<Integer> statuses(List<ResponseEntity<JsonNode>> results) {
		return results.stream().map(r -> r.getStatusCode().value()).toList();
	}

}
