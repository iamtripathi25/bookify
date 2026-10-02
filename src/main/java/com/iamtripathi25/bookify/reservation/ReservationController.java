package com.iamtripathi25.bookify.reservation;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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

	/** The user is the token's subject; the body has no user field to spoof. */
	@PostMapping("/shows/{showId}/reserve")
	ResponseEntity<Reservation> reserve(@PathVariable UUID showId, @AuthenticationPrincipal Jwt jwt,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody ReserveRequest request) {
		Reservation reservation = reservations.reserve(showId, jwt.getSubject(), request.seats(), idempotencyKey,
				request.idempotencyKey());
		return ResponseEntity.created(URI.create("/reservations/" + reservation.id())).body(reservation);
	}

	record ReserveRequest(List<String> seats, String idempotencyKey) {
	}

}
