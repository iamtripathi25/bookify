package com.iamtripathi25.bookify.error;

import org.springframework.http.HttpStatus;

public class IdempotencyMismatchException extends ApiException {

	public IdempotencyMismatchException() {
		super(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
				"Idempotency key was already used for a different request");
	}

}
