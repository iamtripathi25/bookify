package com.iamtripathi25.bookify;

import java.sql.Connection;

import javax.sql.DataSource;

import com.iamtripathi25.bookify.config.DbHealthIndicator;
import com.iamtripathi25.bookify.config.HealthDbPool;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A database that stops answering (dropped packets, not refused) must still fail readiness fast. */
class DbHealthIndicatorTest {

	@Test
	void aHangingDatabaseFailsWithinTheDeadline() throws Exception {
		DataSource hanging = mock(DataSource.class);
		when(hanging.getConnection()).thenAnswer(inv -> {
			Thread.sleep(30_000);
			return mock(Connection.class);
		});
		HealthDbPool pool = mock(HealthDbPool.class);
		when(pool.dataSource()).thenReturn(hanging);
		DbHealthIndicator indicator = new DbHealthIndicator(pool, 1);

		long start = System.nanoTime();
		Health health = indicator.health();
		long elapsedMs = (System.nanoTime() - start) / 1_000_000;

		assertThat(health.getStatus()).isEqualTo(Status.DOWN);
		assertThat(elapsedMs).isBetween(900L, 2_000L);
		indicator.destroy();
	}

}
