package com.iamtripathi25.bookify.reservation;

import java.util.List;
import java.util.Optional;
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

	/**
	 * B1: claims the idempotency key. If a twin with the same (user, key) is in flight, this blocks
	 * on the unique index until the twin's transaction ends: if it committed, nothing is inserted
	 * and this returns false; if it rolled back, the insert goes ahead.
	 * @return true if this call claimed the key
	 */
	public boolean claim(Reservation r, String idempotencyKey, String requestHash) {
		List<UUID> inserted = jdbc.queryForList("""
				INSERT INTO reservations
				  (id, show_id, user_id, seats, amount_paise, status, idempotency_key, request_hash)
				VALUES (:id, :show, :user, :seats, :amount, :status, :key, :hash)
				ON CONFLICT (user_id, idempotency_key) DO NOTHING
				RETURNING id
				""", new MapSqlParameterSource()
			.addValue("id", r.id())
			.addValue("show", r.showId())
			.addValue("user", r.userId())
			.addValue("seats", PgArrays.text(r.seats()))
			.addValue("amount", r.amountPaise())
			.addValue("status", r.status())
			.addValue("key", idempotencyKey)
			.addValue("hash", requestHash), UUID.class);
		return !inserted.isEmpty();
	}

	/** The reservation stored under a user's idempotency key, with its request hash. */
	public Optional<Keyed> findByKey(String userId, String idempotencyKey) {
		return jdbc.query("""
				SELECT id, show_id, user_id, seats, amount_paise, status, request_hash
				FROM reservations WHERE user_id = :user AND idempotency_key = :key
				""", new MapSqlParameterSource().addValue("user", userId).addValue("key", idempotencyKey),
				(rs, i) -> new Keyed(new Reservation(rs.getObject("id", UUID.class),
						rs.getObject("show_id", UUID.class), rs.getString("user_id"),
						List.of((String[]) rs.getArray("seats").getArray()), rs.getLong("amount_paise"),
						rs.getString("status")), rs.getString("request_hash")))
			.stream()
			.findFirst();
	}

	/**
	 * B2: adds {@code seats} to the user's count for the show, only if the result stays within the
	 * limit. ON CONFLICT DO UPDATE takes the counter's row lock and re-evaluates the WHERE against
	 * the latest committed value, so concurrent requests from one user are serialised here.
	 * @return true if the quota was claimed
	 */
	public boolean claimQuota(UUID showId, String userId, int seats, int limit) {
		List<Integer> held = jdbc.queryForList("""
				INSERT INTO user_show_counts (show_id, user_id, held) VALUES (:show, :user, :n)
				ON CONFLICT (show_id, user_id) DO UPDATE
				  SET held = user_show_counts.held + EXCLUDED.held
				  WHERE user_show_counts.held + EXCLUDED.held <= :limit
				RETURNING held
				""", new MapSqlParameterSource().addValue("show", showId)
			.addValue("user", userId)
			.addValue("n", seats)
			.addValue("limit", limit), Integer.class);
		return !held.isEmpty();
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

	public record Keyed(Reservation reservation, String requestHash) {
	}

	public record SeatState(String label, SeatStatus status) {

		static final RowMapper<SeatState> MAPPER = (rs, i) -> new SeatState(
				rs.getString("label"), SeatStatus.fromDb(rs.getString("status")));

	}

}
