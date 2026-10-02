package com.iamtripathi25.bookify.show;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.iamtripathi25.bookify.db.PgArrays;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ShowRepository {

	private final NamedParameterJdbcTemplate jdbc;

	public ShowRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** Inserts the show and all its seats as available. Must run inside a transaction. */
	public void insert(Show show, List<String> labels) {
		jdbc.update("""
				INSERT INTO shows (id, name, price_paise, per_user_limit, total_seats)
				VALUES (:id, :name, :price, :limit, :total)
				""", new MapSqlParameterSource()
			.addValue("id", show.id())
			.addValue("name", show.name())
			.addValue("price", show.pricePaise())
			.addValue("limit", show.perUserLimit())
			.addValue("total", show.totalSeats()));
		// One statement for every seat; WITH ORDINALITY keeps the caller's display order.
		jdbc.update("""
				INSERT INTO seats (show_id, label, seat_no, status)
				SELECT :id, s.label, s.ord::int, 'available'
				FROM unnest(:labels) WITH ORDINALITY AS s(label, ord)
				""", new MapSqlParameterSource()
			.addValue("id", show.id())
			.addValue("labels", PgArrays.text(labels)));
	}

	public Optional<Show> findById(UUID id) {
		return jdbc.query("""
				SELECT id, name, price_paise, per_user_limit, total_seats FROM shows WHERE id = :id
				""", new MapSqlParameterSource("id", id),
				(rs, i) -> new Show(rs.getObject("id", UUID.class), rs.getString("name"), rs.getLong("price_paise"),
						rs.getInt("per_user_limit"), rs.getInt("total_seats")))
			.stream()
			.findFirst();
	}

	/** Every seat of a show in display order, read in one statement (one snapshot). */
	public List<SeatView> findSeats(UUID showId) {
		return jdbc.query("""
				SELECT label, status FROM seats WHERE show_id = :id ORDER BY seat_no
				""", new MapSqlParameterSource("id", showId),
				(rs, i) -> new SeatView(rs.getString("label"), SeatStatus.fromDb(rs.getString("status"))));
	}

	public record SeatView(String label, SeatStatus status) {
	}

}
