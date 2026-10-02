package com.iamtripathi25.bookify.error;

import java.util.List;

import org.springframework.http.HttpStatus;

public class UnknownSeatException extends ApiException {

	public UnknownSeatException(List<String> labels) {
		super(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Unknown seats: " + String.join(", ", labels));
	}

}
