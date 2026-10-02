package com.iamtripathi25.bookify.config;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Registered as the "db" health contributor (bean name minus the HealthIndicator suffix) and
 * included in the readiness group. Runs {@code SELECT 1} on the health pool and fails closed.
 *
 * <p>The check has a hard deadline. Driver timeouts alone aren't enough: when packets are dropped
 * (a network partition, a frozen database) rather than refused, a connection attempt can stall
 * through several timeouts in a row. The deadline keeps readiness at ~2s whatever the driver does.
 */
@Component("dbHealthIndicator")
public class DbHealthIndicator implements HealthIndicator, DisposableBean {

	private final HealthDbPool healthDb;

	private final long deadlineMillis;

	private final ExecutorService checks = Executors.newVirtualThreadPerTaskExecutor();

	public DbHealthIndicator(HealthDbPool healthDb, @Value("${bookify.health-db.timeout-seconds}") int timeoutSeconds) {
		this.healthDb = healthDb;
		this.deadlineMillis = timeoutSeconds * 1000L;
	}

	@Override
	public Health health() {
		Future<?> check = checks.submit(() -> {
			try (Connection c = healthDb.dataSource().getConnection(); Statement st = c.createStatement()) {
				st.setQueryTimeout((int) Math.max(1, deadlineMillis / 1000));
				try (ResultSet rs = st.executeQuery("SELECT 1")) {
					rs.next();
				}
			}
			return null;
		});
		try {
			check.get(deadlineMillis, TimeUnit.MILLISECONDS);
			return Health.up().withDetail("database", "PostgreSQL").build();
		}
		catch (TimeoutException ex) {
			// The stuck attempt finishes on its own when the driver's socket timeout fires.
			check.cancel(true);
			return Health.down().withDetail("error", "no response within " + deadlineMillis + "ms").build();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return Health.down(ex).build();
		}
		catch (Exception ex) {
			return Health.down(ex.getCause() != null ? ex.getCause() : ex).build();
		}
	}

	@Override
	public void destroy() {
		checks.shutdownNow();
	}

}
