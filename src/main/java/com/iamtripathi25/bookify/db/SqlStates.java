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

	/** True for any outcome that means "the database was too busy", which maps to 409 CONTENTION. */
	public static boolean isContention(Throwable ex) {
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
