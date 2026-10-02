package com.iamtripathi25.bookify.error;

import org.springframework.http.HttpStatus;

public class ForbiddenException extends ApiException {

	public ForbiddenException(String message) {
		super(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
	}

}
