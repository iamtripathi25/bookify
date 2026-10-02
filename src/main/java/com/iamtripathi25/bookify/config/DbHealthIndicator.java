package com.iamtripathi25.bookify.config;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Registered as the "db" health contributor (bean name minus the HealthIndicator suffix) and
 * included in the readiness group. Runs {@code SELECT 1} on the health pool and fails closed.
 */
@Component("dbHealthIndicator")
public class DbHealthIndicator implements HealthIndicator {

	private final HealthDbPool healthDb;

	public DbHealthIndicator(HealthDbPool healthDb) {
		this.healthDb = healthDb;
	}

	@Override
	public Health health() {
		try (Connection c = healthDb.dataSource().getConnection();
				Statement st = c.createStatement()) {
			st.setQueryTimeout(2);
			try (ResultSet rs = st.executeQuery("SELECT 1")) {
				rs.next();
			}
			return Health.up().withDetail("database", "PostgreSQL").build();
		}
		catch (Exception ex) {
			return Health.down(ex).build();
		}
	}

}
