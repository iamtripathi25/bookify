package com.iamtripathi25.bookify.show;

import java.util.UUID;

/** A show's immutable header. Money is integer paise. */
public record Show(UUID id, String name, long pricePaise, int perUserLimit, int totalSeats) {
}
