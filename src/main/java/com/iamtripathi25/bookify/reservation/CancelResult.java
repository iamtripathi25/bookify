package com.iamtripathi25.bookify.reservation;

/** A cancel's outcome. {@code changed} is false when the reservation was already cancelled. */
public record CancelResult(Reservation reservation, boolean changed) {
}
