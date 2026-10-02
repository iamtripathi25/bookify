package com.iamtripathi25.bookify.reservation;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/** API body for a reservation. Money is integer paise. */
public record Reservation(@JsonProperty("reservation_id") UUID id, UUID showId, String userId, List<String> seats,
		long amountPaise, String status) {

	public static final String CONFIRMED = "confirmed";

	public static final String CANCELLED = "cancelled";

}
