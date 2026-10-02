package com.iamtripathi25.bookify.reservation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.iamtripathi25.bookify.db.SqlStates;
import com.iamtripathi25.bookify.error.ContentionException;
import com.iamtripathi25.bookify.error.IdempotencyMismatchException;
import com.iamtripathi25.bookify.error.InvalidRequestException;
import com.iamtripathi25.bookify.error.PerUserLimitException;
import com.iamtripathi25.bookify.error.SeatTakenException;
import com.iamtripathi25.bookify.error.UnknownSeatException;
import com.iamtripathi25.bookify.reservation.ReservationRepository.Keyed;
import com.iamtripathi25.bookify.reservation.ReservationRepository.SeatState;
import com.iamtripathi25.bookify.show.SeatStatus;
import com.iamtripathi25.bookify.show.Show;
import com.iamtripathi25.bookify.show.ShowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Reservation entry point. Deliberately not transactional: Phase A runs cheap autocommit reads so
 * most losers of a hot seat are turned away after one short query, without ever opening a write
 * transaction. Phase B runs in {@link ReservationTxService}, retried here on deadlock or
 * serialization failure (a retry inside the transaction would run on an aborted transaction).
 */
@Service
public class ReservationFacade {

	private static final Logger log = LoggerFactory.getLogger(ReservationFacade.class);

	static final int MAX_RETRIES = 3;

	static final int MAX_KEY_LENGTH = 128;

	static final int MAX_LABEL_LENGTH = 32;

	private final ShowService shows;

	private final ReservationRepository repository;

	private final ReservationTxService tx;

	public ReservationFacade(ShowService shows, ReservationRepository repository, ReservationTxService tx) {
		this.shows = shows;
		this.repository = repository;
		this.tx = tx;
	}

	/**
	 * Books all requested seats or none (all-or-nothing).
	 * @param headerKey the Idempotency-Key header; wins over the body key when both are present
	 */
	public ReserveResult reserve(UUID showId, String userId, List<String> requestedSeats, String headerKey,
			String bodyKey) {
		// A1: validate and normalise.
		String key = idempotencyKey(headerKey, bodyKey);
		List<String> labels = normaliseSeats(requestedSeats);
		String requestHash = requestHash(showId, labels);

		// A2: the show (cached) and the per-request limit.
		Show show = shows.get(showId);
		if (labels.size() > show.perUserLimit()) {
			throw new PerUserLimitException(show.perUserLimit());
		}

		// A3: lock-free read of the requested seats.
		Map<String, SeatStatus> current = repository.readSeats(showId, labels)
			.stream()
			.collect(Collectors.toMap(SeatState::label, SeatState::status));
		List<String> unknown = labels.stream().filter(l -> !current.containsKey(l)).toList();
		if (!unknown.isEmpty()) {
			throw new UnknownSeatException(unknown);
		}

		// A4: idempotency lookup. It must come after A3, never before: a reservation commits together
		// with its seats, so any booking A3 saw is visible to this later read, and a retry of a
		// successful booking replays instead of being declined as seat-taken.
		Optional<Keyed> existing = repository.findByKey(userId, key);
		if (existing.isPresent()) {
			if (!existing.get().requestHash().equals(requestHash)) {
				throw new IdempotencyMismatchException();
			}
			return ReserveResult.replayed(existing.get().reservation());
		}

		// A5: decline early if anything is already gone, without opening a write transaction.
		List<String> taken = labels.stream().filter(l -> current.get(l) != SeatStatus.AVAILABLE).toList();
		if (!taken.isEmpty()) {
			throw new SeatTakenException(taken);
		}

		// Phase B.
		long amount = Math.multiplyExact(show.pricePaise(), labels.size());
		Reservation reservation = new Reservation(UUID.randomUUID(), showId, userId, labels, amount,
				Reservation.CONFIRMED);
		return withRetry(() -> tx.reserve(reservation, key, requestHash, show.perUserLimit()));
	}

	private static ReserveResult withRetry(Supplier<ReserveResult> attempt) {
		for (int retry = 0;; retry++) {
			try {
				return attempt.get();
			}
			catch (RuntimeException ex) {
				if (!SqlStates.isRetryable(ex)) {
					throw ex;
				}
				if (retry >= MAX_RETRIES) {
					log.warn("Giving up after {} retries (sqlstate={})", MAX_RETRIES, SqlStates.sqlState(ex));
					throw new ContentionException();
				}
				sleepJitter();
			}
		}
	}

	/** 10–50ms, so retried transactions don't collide again in lockstep. */
	private static void sleepJitter() {
		try {
			Thread.sleep(ThreadLocalRandom.current().nextLong(10, 51));
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new ContentionException();
		}
	}

	private static String idempotencyKey(String headerKey, String bodyKey) {
		String key = headerKey != null && !headerKey.isBlank() ? headerKey : bodyKey;
		if (key == null || key.isBlank()) {
			throw new InvalidRequestException("Idempotency key is required (Idempotency-Key header or idempotency_key)");
		}
		if (key.length() > MAX_KEY_LENGTH) {
			throw new InvalidRequestException("Idempotency key must be at most " + MAX_KEY_LENGTH + " characters");
		}
		return key;
	}

	/** Non-empty, no blanks, no duplicates; returned sorted so equivalent requests look identical. */
	private static List<String> normaliseSeats(List<String> seats) {
		if (seats == null || seats.isEmpty()) {
			throw new InvalidRequestException("seats must contain at least one seat");
		}
		Set<String> seen = new HashSet<>();
		for (String seat : seats) {
			if (seat == null || seat.isBlank() || seat.length() > MAX_LABEL_LENGTH) {
				throw new InvalidRequestException("seats must be non-blank labels of at most " + MAX_LABEL_LENGTH
						+ " characters");
			}
			if (!seen.add(seat)) {
				throw new InvalidRequestException("Duplicate seat: " + seat);
			}
		}
		return seats.stream().sorted().toList();
	}

	/**
	 * SHA-256 of the show id and the sorted labels, so [A13, A12] and [A12, A13] hash the same.
	 * Each label is length-prefixed so no choice of label text can make two requests collide.
	 */
	static String requestHash(UUID showId, List<String> sortedLabels) {
		StringBuilder canonical = new StringBuilder(showId.toString());
		for (String label : sortedLabels) {
			canonical.append('|').append(label.length()).append(':').append(label);
		}
		try {
			MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(sha256.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
