package com.iamtripathi25.bookify.error;

import org.springframework.http.HttpStatus;

public class NotFoundException extends ApiException {

	public NotFoundException(String message) {
		super(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
	}

}
