package com.iamtripathi25.bookify.error;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.iamtripathi25.bookify.config.LogContext;
import org.slf4j.MDC;

/** The single error shape for every non-2xx response. {@code seats} appears only for SEAT_TAKEN. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorBody(String code, String message, List<String> seats, String requestId) {

	public static ErrorBody of(String code, String message) {
		return of(code, message, null);
	}

	public static ErrorBody of(String code, String message, List<String> seats) {
		return new ErrorBody(code, message, seats, MDC.get(LogContext.REQUEST_ID));
	}

}
