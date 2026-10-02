package com.iamtripathi25.bookify.reservation;

import java.util.List;

import com.iamtripathi25.bookify.error.IdempotencyMismatchException;
import com.iamtripathi25.bookify.error.PerUserLimitException;
import com.iamtripathi25.bookify.error.SeatTakenException;
import com.iamtripathi25.bookify.reservation.ReservationRepository.Keyed;
import com.iamtripathi25.bookify.reservation.ReservationRepository.SeatState;
import com.iamtripathi25.bookify.show.SeatStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase B: the only place seats change hands. Every writer takes its locks in one global order:
 * the reservation's key (B1), then the user's counter (B2), then seats in ascending label order
 * (B3). Domain exceptions are thrown out of the method so the whole transaction rolls back: a
 * declined attempt leaves no reservation row, no quota and no seat behind, and a retry with the
 * same key simply tries again.
 */
@Service
public class ReservationTxService {

	private final ReservationRepository repository;

	public ReservationTxService(ReservationRepository repository) {
		this.repository = repository;
	}

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public ReserveResult reserve(Reservation reservation, String idempotencyKey, String requestHash, int perUserLimit) {
		List<String> labels = reservation.seats();

		// B1: claim the idempotency key. Never catch DuplicateKeyException here instead: Postgres has
		// already aborted the transaction by then. ON CONFLICT reports the conflict without an error.
		if (!repository.claim(reservation, idempotencyKey, requestHash)) {
			// A twin committed first; under READ COMMITTED this new statement sees it.
			Keyed twin = repository.findByKey(reservation.userId(), idempotencyKey).orElseThrow();
			if (!twin.requestHash().equals(requestHash)) {
				throw new IdempotencyMismatchException();
			}
			return ReserveResult.replayed(twin.reservation());
		}

		// B2: claim the per-user quota.
		if (!repository.claimQuota(reservation.showId(), reservation.userId(), labels.size(), perUserLimit)) {
			throw new PerUserLimitException(perUserLimit);
		}

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
		return ReserveResult.created(reservation);
	}

}
