package com.iamtripathi25.bookify.reservation;

/**
 * A reserve call's outcome. {@code replay} is true when the idempotency key had already booked:
 * the reservation is the original one in its current state, and the API answers 200, not 201.
 */
public record ReserveResult(Reservation reservation, boolean replay) {

	static ReserveResult created(Reservation reservation) {
		return new ReserveResult(reservation, false);
	}

	static ReserveResult replayed(Reservation reservation) {
		return new ReserveResult(reservation, true);
	}

}
