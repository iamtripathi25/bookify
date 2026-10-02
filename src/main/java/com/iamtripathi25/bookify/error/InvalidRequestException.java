package com.iamtripathi25.bookify.error;

import org.springframework.http.HttpStatus;

public class InvalidRequestException extends ApiException {

	public InvalidRequestException(String message) {
		super(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
	}

}
