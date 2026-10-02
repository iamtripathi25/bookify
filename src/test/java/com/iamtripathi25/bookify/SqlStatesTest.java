package com.iamtripathi25.bookify;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import com.iamtripathi25.bookify.db.SqlStates;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;

import static org.assertj.core.api.Assertions.assertThat;

class SqlStatesTest {

	@Test
	void busyPoolOnAHealthyDatabaseIsContention() {
		// What Hikari throws when every connection is in use: no SQLSTATE, no connection failure.
		var poolTimeout = new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection",
				new SQLTransientConnectionException("main - Connection is not available, request timed out"));
		assertThat(SqlStates.isContention(poolTimeout)).isTrue();
		assertThat(SqlStates.isUnavailable(poolTimeout)).isFalse();
	}

	@Test
	void unreachableDatabaseIsUnavailableNotContention() {
		// What Hikari throws when it can't create a connection: the failure's 08xxx state is attached.
		var refused = new SQLException("Connection to postgres:5432 refused", "08001");
		var poolTimeout = new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection",
				new SQLTransientConnectionException("main - Connection is not available", "08001", refused));
		assertThat(SqlStates.isUnavailable(poolTimeout)).isTrue();
		assertThat(SqlStates.isContention(poolTimeout)).isFalse();

		var lostMidTransaction = new CannotCreateTransactionException("Could not open JDBC Connection",
				new SQLException("An I/O error occurred while sending to the backend", "08006"));
		assertThat(SqlStates.isUnavailable(lostMidTransaction)).isTrue();

		var shuttingDown = new DataAccessResourceFailureException("PreparedStatementCallback",
				new SQLException("FATAL: terminating connection due to administrator command", "57P01"));
		assertThat(SqlStates.isUnavailable(shuttingDown)).isTrue();
		assertThat(SqlStates.isContention(shuttingDown)).isFalse();
	}

	@Test
	void lockTimeoutsAndDeadlocksAreContention() {
		var lockTimeout = new CannotAcquireLockException("lock", new SQLException("lock timeout", "55P03"));
		var deadlock = new CannotAcquireLockException("deadlock", new SQLException("deadlock", "40P01"));
		assertThat(SqlStates.isContention(lockTimeout)).isTrue();
		assertThat(SqlStates.isContention(deadlock)).isTrue();
		assertThat(SqlStates.isRetryable(deadlock)).isTrue();
		assertThat(SqlStates.isRetryable(lockTimeout)).isFalse();
		assertThat(SqlStates.isUnavailable(deadlock)).isFalse();
	}

}
