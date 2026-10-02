package com.iamtripathi25.bookify.error;

import org.springframework.http.HttpStatus;

/**
 * Postgres can't be reached (outage or network partition). The service refuses rather than guess:
 * nothing was booked, and retrying with the same idempotency key is safe once it's back.
 */
public class DatabaseUnavailableException extends ApiException {

	/** Seconds a client should wait before retrying; readiness recovers within ~1s of the database. */
	public static final int RETRY_AFTER_SECONDS = 5;

	public DatabaseUnavailableException() {
		super(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE", "Database unavailable, retry later");
	}

}
