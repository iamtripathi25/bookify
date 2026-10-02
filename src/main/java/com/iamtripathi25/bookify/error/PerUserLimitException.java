package com.iamtripathi25.bookify.error;

import org.springframework.http.HttpStatus;

public class PerUserLimitException extends ApiException {

	public PerUserLimitException(int limit) {
		super(HttpStatus.CONFLICT, "PER_USER_LIMIT", "Per-user limit of " + limit + " seats for this show reached");
	}

}
