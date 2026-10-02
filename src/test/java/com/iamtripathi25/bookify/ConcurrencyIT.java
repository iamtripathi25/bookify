package com.iamtripathi25.bookify;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.UUID;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.iamtripathi25.bookify.error.SeatTakenException;
import com.iamtripathi25.bookify.reservation.Reservation;
import com.iamtripathi25.bookify.reservation.ReservationTxService;
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
		int racers = 100;
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
		int pairs = 50;
		List<String> tokens = IntStream.range(0, pairs * 2).mapToObj(i -> token("pair-" + i)).toList();

		// Half ask for [A1, A2], half for [A2, A1].
		List<ResponseEntity<JsonNode>> results = race(pairs * 2, i -> reserve(show, tokens.get(i),
				i % 2 == 0 ? List.of("A1", "A2") : List.of("A2", "A1"), newKey()));

		assertThat(results.stream().filter(r -> r.getStatusCode().value() == 201)).hasSize(1);
		results.stream()
			.filter(r -> r.getStatusCode().value() != 201)
			.forEach(r -> assertError(r, 409, "SEAT_TAKEN"));
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
				newKey(), "hash"));

		assertThat(outcomes.stream().filter(Reservation.class::isInstance)).hasSize(1);
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
					newKey(), "hash");
		});

		assertThat(outcomes).allMatch(o -> o instanceof Reservation || o instanceof SeatTakenException,
				"only a win or SEAT_TAKEN, never a deadlock");
		assertThat(outcomes.stream().filter(Reservation.class::isInstance)).hasSize(1);
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
