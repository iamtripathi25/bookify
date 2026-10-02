package com.iamtripathi25.bookify.db;

import java.sql.SQLException;
import java.util.Set;

import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;

/** Classifies database failures by Postgres SQLState, independent of Spring's translation. */
public final class SqlStates {

	public static final String DEADLOCK = "40P01";

	public static final String SERIALIZATION_FAILURE = "40001";

	public static final String LOCK_NOT_AVAILABLE = "55P03";

	public static final String QUERY_CANCELED = "57014";

	/** SQLSTATE class 08: connection exceptions (can't connect, connection lost). */
	public static final String CONNECTION_EXCEPTION_CLASS = "08";

	/** Admin shutdown, crash shutdown, cannot connect now (starting up or recovering). */
	private static final Set<String> SERVER_UNAVAILABLE = Set.of("57P01", "57P02", "57P03");

	/** Safe to retry in a fresh transaction: Postgres aborted ours to break a cycle. */
	private static final Set<String> RETRYABLE = Set.of(DEADLOCK, SERIALIZATION_FAILURE);

	/** Lock or statement timeouts: not retried, so a request's worst case stays bounded. */
	private static final Set<String> TIMEOUTS = Set.of(LOCK_NOT_AVAILABLE, QUERY_CANCELED);

	private SqlStates() {
	}

	public static boolean isRetryable(Throwable ex) {
		String state = sqlState(ex);
		return state != null && RETRYABLE.contains(state);
	}

	/**
	 * True when Postgres can't be used at all: a connection-class error (SQLSTATE 08xxx, can't
	 * connect or connection lost) or the server shutting down or starting up (57P01-57P03) anywhere
	 * in the cause chain. A healthy pool that is merely busy times out without one (Hikari only
	 * attaches a connection failure when creating a connection actually failed). Maps to 503.
	 */
	public static boolean isUnavailable(Throwable ex) {
		for (Throwable t = ex; t != null; t = t.getCause()) {
			if (t instanceof SQLException sql && sql.getSQLState() != null) {
				String state = sql.getSQLState();
				if (state.startsWith(CONNECTION_EXCEPTION_CLASS) || SERVER_UNAVAILABLE.contains(state)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * True for any outcome that means "the database was up but too busy to decide in time", which
	 * maps to 409 CONTENTION. An unreachable database is not contention; see {@link #isUnavailable}.
	 */
	public static boolean isContention(Throwable ex) {
		if (isUnavailable(ex)) {
			return false;
		}
		if (ex instanceof CannotGetJdbcConnectionException || ex instanceof CannotCreateTransactionException
				|| ex instanceof QueryTimeoutException) {
			return true;
		}
		String state = sqlState(ex);
		return state != null && (RETRYABLE.contains(state) || TIMEOUTS.contains(state));
	}

	/** The SQLState of the first SQLException in the cause chain, or null. */
	public static String sqlState(Throwable ex) {
		for (Throwable t = ex; t != null; t = t.getCause()) {
			if (t instanceof SQLException sql && sql.getSQLState() != null) {
				return sql.getSQLState();
			}
		}
		return null;
	}

}
