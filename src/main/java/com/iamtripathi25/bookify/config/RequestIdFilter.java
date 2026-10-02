package com.iamtripathi25.bookify.config;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Outermost filter (ahead of Spring Security, so even 401s carry an id). Takes the caller's
 * X-Request-Id or generates one, puts it in the MDC, echoes it in the response, and writes one
 * access-log line per request with status, latency and outcome.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Request-Id";

	/** Caller-supplied ids are echoed into logs and headers, so only accept tame ones. */
	private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

	private static final Logger access = LoggerFactory.getLogger("bookify.access");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String supplied = request.getHeader(HEADER);
		String requestId = supplied != null && SAFE_ID.matcher(supplied).matches() ? supplied
				: UUID.randomUUID().toString();
		MDC.put(LogContext.REQUEST_ID, requestId);
		response.setHeader(HEADER, requestId);
		long start = System.nanoTime();
		try {
			chain.doFilter(request, response);
		}
		finally {
			// Health checks and scrapes run every few seconds; logging them would bury real traffic.
			if (!request.getRequestURI().startsWith("/actuator")) {
				long latencyMs = (System.nanoTime() - start) / 1_000_000;
				access.info("{} {} {} {}", kv("method", request.getMethod()), kv("path", request.getRequestURI()),
						kv("status", response.getStatus()), kv("latency_ms", latencyMs));
			}
			MDC.clear();
		}
	}

}
