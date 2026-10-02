package com.iamtripathi25.bookify.config;

import java.util.ArrayList;
import java.util.List;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.MultiGauge.Row;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Seat gauges read from the database, not kept in memory, so they always agree with
 * GET /shows/{id}:
 * <ul>
 * <li>{@code bookify_seats{show,status}}: seats per status</li>
 * <li>{@code bookify_show_seats{show}}: the show's total, for the reconciliation check
 * {@code sum by (show) (bookify_seats) == bookify_show_seats}</li>
 * </ul>
 * Refreshed every second on the small health pool, so they keep flowing during a burst that has
 * the main pool saturated. Values are therefore at most about a second old.
 */
@Component
public class SeatGauges {

	private static final Logger log = LoggerFactory.getLogger(SeatGauges.class);

	private static final List<String> STATUSES = List.of("available", "held", "confirmed");

	private final JdbcTemplate healthJdbc;

	private final MultiGauge seats;

	private final MultiGauge totals;

	private volatile boolean failing;

	public SeatGauges(HealthDbPool healthDb, MeterRegistry registry) {
		this.healthJdbc = new JdbcTemplate(healthDb.dataSource());
		this.seats = MultiGauge.builder("bookify.seats").description("Seats per show and status").register(registry);
		this.totals = MultiGauge.builder("bookify.show.seats")
			.description("Total seats per show")
			.register(registry);
	}

	@Scheduled(fixedDelay = 1000, initialDelay = 1000)
	public void refresh() {
		try {
			List<Row<?>> seatRows = new ArrayList<>();
			List<Row<?>> totalRows = new ArrayList<>();
			// One statement, so the per-status counts and the total come from the same snapshot.
			healthJdbc.query("""
					SELECT s.id::text AS show,
					       s.total_seats,
					       count(*) FILTER (WHERE st.status = 'available') AS available,
					       count(*) FILTER (WHERE st.status = 'held')      AS held,
					       count(*) FILTER (WHERE st.status = 'confirmed') AS confirmed
					FROM shows s JOIN seats st ON st.show_id = s.id
					GROUP BY s.id, s.total_seats
					""", rs -> {
				String show = rs.getString("show");
				for (String status : STATUSES) {
					seatRows.add(Row.of(Tags.of("show", show, "status", status), rs.getLong(status)));
				}
				totalRows.add(Row.of(Tags.of("show", show), rs.getLong("total_seats")));
			});
			seats.register(seatRows, true);
			totals.register(totalRows, true);
			if (failing) {
				log.info("Seat gauges recovered");
				failing = false;
			}
		}
		catch (RuntimeException ex) {
			// Keep the last values; readiness already reports the database as down.
			if (!failing) {
				log.warn("Seat gauges could not refresh: {}", ex.getMessage());
				failing = true;
			}
		}
	}

}
