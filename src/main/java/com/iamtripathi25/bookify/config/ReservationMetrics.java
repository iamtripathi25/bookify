package com.iamtripathi25.bookify.config;

import java.util.List;
import java.util.UUID;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Reservation outcome counters. Callers increment them only after the transaction has committed
 * (or after a decline was decided), so rolled-back attempts never count as confirmed.
 *
 * <p>Counters reset when the process restarts. The DB-backed {@code bookify_seats} gauge is the
 * source of truth; reconcile against counter deltas within one run.
 */
@Component
public class ReservationMetrics {

	public static final String SEAT_TAKEN = "seat_taken";

	public static final String PER_USER_LIMIT = "per_user_limit";

	public static final String IDEMPOTENT_REPLAY = "idempotent_replay";

	public static final String IDEMPOTENCY_MISMATCH = "idempotency_mismatch";

	public static final String CONTENTION = "contention";

	private final MeterRegistry registry;

	public ReservationMetrics(MeterRegistry registry) {
		this.registry = registry;
	}

	private static final List<String> REASONS = List.of(SEAT_TAKEN, PER_USER_LIMIT,
			IDEMPOTENT_REPLAY, IDEMPOTENCY_MISMATCH, CONTENTION);

	/**
	 * Registers every counter for a new show at 0. Prometheus then scrapes a zero before the first
	 * booking, so rates and increases over a burst include its first second instead of treating the
	 * burst's opening count as the starting point.
	 */
	public void initShow(UUID showId) {
		counter("bookify.reservations.confirmed", "Reservations created", showId);
		counter("bookify.seats.confirmed", "Seats booked by new reservations", showId);
		counter("bookify.reservations.replayed", "Retries answered with the original reservation", showId);
		counter("bookify.reservations.cancelled", "Reservations cancelled", showId);
		counter("bookify.seats.released", "Seats released by cancels", showId);
		REASONS.forEach(reason -> declinedCounter(showId, reason));
	}

	/** A new reservation committed, booking {@code seats} seats. */
	public void confirmed(UUID showId, int seats) {
		counter("bookify.reservations.confirmed", "Reservations created", showId).increment();
		counter("bookify.seats.confirmed", "Seats booked by new reservations", showId).increment(seats);
	}

	/**
	 * A request turned away. A replay counts here too (reason idempotent_replay), matching the
	 * assignment's list of decline reasons; {@link #replayed} counts it separately for dashboards.
	 */
	public void declined(UUID showId, String reason) {
		declinedCounter(showId, reason).increment();
	}

	private Counter declinedCounter(UUID showId, String reason) {
		return Counter.builder("bookify.reservations.declined")
			.description("Reserve requests that booked nothing, by reason")
			.tag("show", String.valueOf(showId))
			.tag("reason", reason)
			.register(registry);
	}

	public void replayed(UUID showId) {
		declined(showId, IDEMPOTENT_REPLAY);
		counter("bookify.reservations.replayed", "Retries answered with the original reservation", showId)
			.increment();
	}

	/** A cancel committed, releasing {@code seats} seats. Repeat cancels (no-ops) don't count. */
	public void cancelled(UUID showId, int seats) {
		counter("bookify.reservations.cancelled", "Reservations cancelled", showId).increment();
		counter("bookify.seats.released", "Seats released by cancels", showId).increment(seats);
	}

	private Counter counter(String name, String description, UUID showId) {
		return Counter.builder(name).description(description).tag("show", String.valueOf(showId)).register(registry);
	}

}
