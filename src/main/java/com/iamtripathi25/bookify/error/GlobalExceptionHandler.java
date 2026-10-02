package com.iamtripathi25.bookify.error;

import java.util.stream.Collectors;

import com.iamtripathi25.bookify.config.LogContext;
import com.iamtripathi25.bookify.db.SqlStates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.Nullable;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps every exception to {@link ErrorBody}. Spring MVC's own exceptions (400 validation and
 * malformed JSON, 400 non-UUID path ids, 404 unknown routes, 405, 415, ...) keep their statuses
 * through {@link ResponseEntityExceptionHandler}; only the body is replaced.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ErrorBody> handleApi(ApiException ex) {
		LogContext.outcomeIfAbsent(ex.code().toLowerCase());
		return ResponseEntity.status(ex.status()).body(ErrorBody.of(ex.code(), ex.getMessage(), ex.seats()));
	}

	/**
	 * An unreachable database is a 503 (refuse, don't guess). Lock/statement timeouts, deadlocks
	 * after retries and pool exhaustion on a healthy database are contention (409), not bugs. Any
	 * other database failure stays a 500.
	 */
	@ExceptionHandler({ DataAccessException.class, TransactionException.class })
	ResponseEntity<ErrorBody> handleDataAccess(RuntimeException ex) {
		if (SqlStates.isUnavailable(ex)) {
			log.warn("Database unavailable: {} (sqlstate={})", ex.getClass().getSimpleName(), SqlStates.sqlState(ex));
			DatabaseUnavailableException unavailable = new DatabaseUnavailableException();
			LogContext.outcomeIfAbsent(unavailable.code().toLowerCase());
			return ResponseEntity.status(unavailable.status())
				.header(HttpHeaders.RETRY_AFTER, String.valueOf(DatabaseUnavailableException.RETRY_AFTER_SECONDS))
				.body(ErrorBody.of(unavailable.code(), unavailable.getMessage()));
		}
		if (SqlStates.isContention(ex)) {
			log.warn("Contention: {} (sqlstate={})", ex.getClass().getSimpleName(), SqlStates.sqlState(ex));
			return handleApi(new ContentionException());
		}
		return handleUnexpected(ex);
	}

	/** Deliberately a 500: hiding bugs as 4xx would corrupt the outcome counts. */
	@ExceptionHandler(Exception.class)
	ResponseEntity<ErrorBody> handleUnexpected(Exception ex) {
		LogContext.outcome("internal_error");
		log.error("Unhandled exception", ex);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
			.body(ErrorBody.of("INTERNAL_ERROR", "Internal server error"));
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		String message = ex.getBindingResult()
			.getFieldErrors()
			.stream()
			.map(e -> e.getField() + " " + e.getDefaultMessage())
			.sorted()
			.collect(Collectors.joining("; "));
		return handleExceptionInternal(ex, ErrorBody.of("INVALID_REQUEST", message), headers, status, request);
	}

	@Override
	protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		String message = ex.getAllErrors()
			.stream()
			.map(e -> e.getDefaultMessage())
			.collect(Collectors.joining("; "));
		return handleExceptionInternal(ex, ErrorBody.of("INVALID_REQUEST", message), headers, status, request);
	}

	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		if (!(body instanceof ErrorBody)) {
			body = ErrorBody.of(codeFor(statusCode), messageFor(ex, statusCode));
		}
		LogContext.outcomeIfAbsent(((ErrorBody) body).code().toLowerCase());
		return ResponseEntity.status(statusCode).headers(headers).body(body);
	}

	private static String codeFor(HttpStatusCode status) {
		return switch (status.value()) {
			case 400 -> "INVALID_REQUEST";
			case 404 -> "NOT_FOUND";
			case 405 -> "METHOD_NOT_ALLOWED";
			case 406 -> "NOT_ACCEPTABLE";
			case 415 -> "UNSUPPORTED_MEDIA_TYPE";
			default -> status.is4xxClientError() ? "CLIENT_ERROR" : "INTERNAL_ERROR";
		};
	}

	private static String messageFor(Exception ex, HttpStatusCode status) {
		if (ex instanceof MethodArgumentTypeMismatchException mismatch) {
			return "Invalid " + mismatch.getName() + ": '" + mismatch.getValue() + "'";
		}
		if (ex instanceof HttpMessageNotReadableException) {
			return "Malformed JSON body";
		}
		return switch (status.value()) {
			case 400 -> "Malformed request";
			case 404 -> "Not found";
			default -> {
				HttpStatus resolved = HttpStatus.resolve(status.value());
				yield resolved != null ? resolved.getReasonPhrase() : ex.getClass().getSimpleName();
			}
		};
	}

}
