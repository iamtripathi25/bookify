package com.iamtripathi25.bookify.show;

import com.fasterxml.jackson.annotation.JsonValue;

public enum SeatStatus {

	AVAILABLE, HELD, CONFIRMED;

	@JsonValue
	public String dbValue() {
		return name().toLowerCase();
	}

	public static SeatStatus fromDb(String value) {
		return valueOf(value.toUpperCase());
	}

}
