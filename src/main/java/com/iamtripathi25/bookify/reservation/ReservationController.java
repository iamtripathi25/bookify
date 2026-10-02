package com.iamtripathi25.bookify.reservation;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReservationController {

	private final ReservationFacade reservations;

	public ReservationController(ReservationFacade reservations) {
		this.reservations = reservations;
	}

	/**
	 * The user is the token's subject; the body has no user field to spoof. 201 for a new booking,
	 * 200 when the idempotency key already booked (the original reservation, in its current state).
	 */
	@PostMapping("/shows/{showId}/reserve")
	ResponseEntity<Reservation> reserve(@PathVariable UUID showId, @AuthenticationPrincipal Jwt jwt,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody ReserveRequest request) {
		ReserveResult result = reservations.reserve(showId, jwt.getSubject(), request.seats(), idempotencyKey,
				request.idempotencyKey());
		Reservation reservation = result.reservation();
		if (result.replay()) {
			return ResponseEntity.ok().location(location(reservation)).body(reservation);
		}
		return ResponseEntity.created(location(reservation)).body(reservation);
	}

	/** Only the owner may cancel. Cancelling twice returns 200 with the cancelled reservation. */
	@PostMapping("/reservations/{id}/cancel")
	Reservation cancel(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
		return reservations.cancel(id, jwt.getSubject());
	}

	/** Only the owner may read; someone else's reservation is a 404, so ids can't be probed. */
	@GetMapping("/reservations/{id}")
	Reservation get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
		return reservations.get(id, jwt.getSubject());
	}

	private static URI location(Reservation reservation) {
		return URI.create("/reservations/" + reservation.id());
	}

	record ReserveRequest(List<String> seats, String idempotencyKey) {
	}

}
