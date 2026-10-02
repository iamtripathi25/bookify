package com.iamtripathi25.bookify.reservation;

import java.util.List;
import java.util.UUID;

import com.iamtripathi25.bookify.db.PgArrays;
import com.iamtripathi25.bookify.show.SeatStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReservationRepository {

	private final NamedParameterJdbcTemplate jdbc;

	public ReservationRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** Phase A pre-check: a plain read, no locks. Unknown labels are simply absent. */
	public List<SeatState> readSeats(UUID showId, List<String> labels) {
		return jdbc.query("""
				SELECT label, status FROM seats WHERE show_id = :show AND label = ANY(:labels)
				""", seatParams(showId, labels), SeatState.MAPPER);
	}

	public void insert(Reservation r, String idempotencyKey, String requestHash) {
		jdbc.update("""
				INSERT INTO reservations
				  (id, show_id, user_id, seats, amount_paise, status, idempotency_key, request_hash)
				VALUES (:id, :show, :user, :seats, :amount, :status, :key, :hash)
				""", new MapSqlParameterSource()
			.addValue("id", r.id())
			.addValue("show", r.showId())
			.addValue("user", r.userId())
			.addValue("seats", PgArrays.text(r.seats()))
			.addValue("amount", r.amountPaise())
			.addValue("status", r.status())
			.addValue("key", idempotencyKey)
			.addValue("hash", requestHash));
	}

	/**
	 * Row-locks the seats in ascending label order. Every writer locks in this same order, so two
	 * overlapping multi-seat requests queue on their first shared seat instead of deadlocking. Under
	 * READ COMMITTED a waiter gets the latest committed row once the lock is granted.
	 */
	public List<SeatState> lockSeats(UUID showId, List<String> labels) {
		return jdbc.query("""
				SELECT label, status FROM seats
				WHERE show_id = :show AND label = ANY(:labels)
				ORDER BY label
				FOR UPDATE
				""", seatParams(showId, labels), SeatState.MAPPER);
	}

	/** Guarded write: only seats still available change. Returns the number of seats taken. */
	public int confirmSeats(UUID showId, List<String> labels, UUID reservationId, String userId) {
		return jdbc.update("""
				UPDATE seats SET status = 'confirmed', reservation_id = :rid, user_id = :user
				WHERE show_id = :show AND label = ANY(:labels) AND status = 'available'
				""", seatParams(showId, labels).addValue("rid", reservationId).addValue("user", userId));
	}

	private static MapSqlParameterSource seatParams(UUID showId, List<String> labels) {
		return new MapSqlParameterSource().addValue("show", showId).addValue("labels", PgArrays.text(labels));
	}

	public record SeatState(String label, SeatStatus status) {

		static final RowMapper<SeatState> MAPPER = (rs, i) -> new SeatState(
				rs.getString("label"), SeatStatus.fromDb(rs.getString("status")));

	}

}
