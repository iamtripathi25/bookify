package com.iamtripathi25.bookify.config;

import org.slf4j.MDC;

/**
 * MDC keys carried on every log line of a request. They become top-level JSON fields, so one
 * request can be followed end to end by its request_id. The MDC is cleared when the request ends.
 */
public final class LogContext {

	public static final String REQUEST_ID = "request_id";

	public static final String USER_ID = "user_id";

	public static final String SHOW_ID = "show_id";

	/** What the request ended as: confirmed, replayed, seat_taken, cancelled, not_found, ... */
	public static final String OUTCOME = "outcome";

	private LogContext() {
	}

	public static void showId(Object showId) {
		MDC.put(SHOW_ID, String.valueOf(showId));
	}

	public static void outcome(String outcome) {
		MDC.put(OUTCOME, outcome);
	}

	/** Sets the outcome unless a more specific one was already recorded. */
	public static void outcomeIfAbsent(String outcome) {
		if (MDC.get(OUTCOME) == null) {
			MDC.put(OUTCOME, outcome);
		}
	}

}
