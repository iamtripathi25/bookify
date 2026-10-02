package com.iamtripathi25.bookify.error;

import java.util.List;

import org.springframework.http.HttpStatus;

public class SeatTakenException extends ApiException {

	private final List<String> seats;

	public SeatTakenException(List<String> seats) {
		super(HttpStatus.CONFLICT, "SEAT_TAKEN", "Seats not available");
		this.seats = List.copyOf(seats);
	}

	@Override
	public List<String> seats() {
		return seats;
	}

}
