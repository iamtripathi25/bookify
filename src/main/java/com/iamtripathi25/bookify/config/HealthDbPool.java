package com.iamtripathi25.bookify.config;

import java.sql.SQLException;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A tiny connection pool reserved for readiness checks and scrape-time metrics, so they never
 * queue behind reservation traffic on the main pool.
 *
 * <p>It is deliberately not a {@link DataSource} bean: declaring a second DataSource bean would
 * switch off Boot's auto-configured main pool. Connection settings are copied from the main pool,
 * which also keeps Testcontainers' {@code @ServiceConnection} working.
 */
@Component
public class HealthDbPool implements DisposableBean {

	private final HikariDataSource pool;

	public HealthDbPool(DataSource mainDataSource, @Value("${bookify.health-db.timeout-seconds}") int timeoutSeconds)
			throws SQLException {
		HikariDataSource main = mainDataSource.unwrap(HikariDataSource.class);
		HikariConfig config = new HikariConfig();
		config.setPoolName("health");
		config.setJdbcUrl(main.getJdbcUrl());
		config.setUsername(main.getUsername());
		config.setPassword(main.getPassword());
		config.setMaximumPoolSize(2);
		config.setMinimumIdle(0);
		config.setConnectionTimeout(timeoutSeconds * 1000L);
		config.setValidationTimeout(Math.min(timeoutSeconds * 1000L, 1000L));
		// Don't fail startup if the DB is down; readiness reports it instead.
		config.setInitializationFailTimeout(-1);
		config.addDataSourceProperty("connectTimeout", String.valueOf(timeoutSeconds));
		config.addDataSourceProperty("socketTimeout", String.valueOf(timeoutSeconds));
		this.pool = new HikariDataSource(config);
	}

	public DataSource dataSource() {
		return pool;
	}

	@Override
	public void destroy() {
		pool.close();
	}

}
