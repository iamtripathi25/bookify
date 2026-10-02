package com.iamtripathi25.bookify.error;

import org.springframework.http.HttpStatus;

/**
 * The database couldn't decide in time: lock timeout, deadlock or serialization failure after
 * retries, or no pool connection within the wait. A clean decline the client may retry, never a 5xx.
 */
public class ContentionException extends ApiException {

	public ContentionException() {
		super(HttpStatus.CONFLICT, "CONTENTION", "Too much contention, retry the request");
	}

}
