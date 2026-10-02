package com.iamtripathi25.bookify.reservation;

import java.util.List;

import com.iamtripathi25.bookify.error.SeatTakenException;
import com.iamtripathi25.bookify.reservation.ReservationRepository.SeatState;
import com.iamtripathi25.bookify.show.SeatStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase B: the only place seats change hands. Lock order is reservation row, then seats in
 * ascending label order. Domain exceptions are thrown out of the method so the whole transaction
 * rolls back; nothing of a declined attempt is left behind.
 */
@Service
public class ReservationTxService {

	private final ReservationRepository repository;

	public ReservationTxService(ReservationRepository repository) {
		this.repository = repository;
	}

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public Reservation reserve(Reservation reservation, String idempotencyKey, String requestHash) {
		List<String> labels = reservation.seats();

		// B1: the reservation row.
		repository.insert(reservation, idempotencyKey, requestHash);

		// B3: lock the seats in label order; re-check status against the latest committed rows.
		List<String> taken = repository.lockSeats(reservation.showId(), labels)
			.stream()
			.filter(s -> s.status() != SeatStatus.AVAILABLE)
			.map(SeatState::label)
			.toList();
		if (!taken.isEmpty()) {
			throw new SeatTakenException(taken);
		}

		// B4: guarded write (defence in depth; the locks above already make it succeed).
		int updated = repository.confirmSeats(reservation.showId(), labels, reservation.id(), reservation.userId());
		if (updated != labels.size()) {
			throw new SeatTakenException(labels);
		}
		return reservation;
	}

}
