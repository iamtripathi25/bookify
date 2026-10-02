package com.iamtripathi25.bookify.error;

import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * Base for domain outcomes that map to a 4xx. Stack traces are skipped: these are expected
 * results under load, not bugs, and are thrown thousands of times per burst.
 */
public abstract class ApiException extends RuntimeException {

	private final HttpStatus status;

	private final String code;

	protected ApiException(HttpStatus status, String code, String message) {
		super(message, null, false, false);
		this.status = status;
		this.code = code;
	}

	public HttpStatus status() {
		return status;
	}

	public String code() {
		return code;
	}

	/** Extra seat labels for the error body; only SEAT_TAKEN sets them. */
	public List<String> seats() {
		return null;
	}

}
