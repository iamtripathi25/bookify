import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reproduces an on-sale stampede against a Bookify deployment and checks every correctness
 * property from the outside. JDK 21+, no dependencies:
 *
 * <pre>
 *   java burst/Burst.java https://bookify.example.com [options]
 * </pre>
 *
 * Steps: mint tokens, create a fresh show, hot-seat storm, mixed stampede (with same-key retries
 * and key reuse) while polling the invariant, limit storm, spoof checks; then print the outcome
 * table and latency, and reconcile responses against GET /shows/{id} and /actuator/prometheus.
 * Exits 1 if any check fails.
 */
public class Burst {

	// ---- options -------------------------------------------------------------------------------

	static String baseUrl;

	static String adminKey = envOr("BOOKIFY_ADMIN_KEY", "local-dev-admin-key");

	static int requests = 20_000;

	static int users = 2_000;

	static int hotStormUsers = 500;

	static int concurrency = 1_000;

	static int hotSeats = 10;

	static long seed = System.nanoTime();

	// ---- state ---------------------------------------------------------------------------------

	static HttpClient client;

	static Semaphore inFlight;

	static final List<String> failures = Collections.synchronizedList(new ArrayList<>());

	static final List<Long> reserveLatenciesMicros = Collections.synchronizedList(new ArrayList<>());

	public static void main(String[] args) throws Exception {
		parseArgs(args);
		HttpClient.Version version = baseUrl.startsWith("https") ? HttpClient.Version.HTTP_2
				: HttpClient.Version.HTTP_1_1;
		client = HttpClient.newBuilder()
			.version(version)
			.connectTimeout(Duration.ofSeconds(10))
			.executor(Executors.newVirtualThreadPerTaskExecutor())
			.build();
		inFlight = new Semaphore(concurrency);
		Random random = new Random(seed);

		banner("Bookify burst against " + baseUrl);
		System.out.printf("requests=%d users=%d hot-storm=%d concurrency=%d seed=%d%n", requests, users,
				hotStormUsers, concurrency, seed);

		// 1. Health and tokens.
		Res ready = send("GET", "/actuator/health/readiness", null, null, Map.of());
		check(ready.status == 200, "readiness is 200 (got " + ready.status + ")");
		String admin = mint("burst-admin", "ADMIN");
		String runId = Long.toString(System.currentTimeMillis(), 36);
		List<String> userIds = new ArrayList<>();
		for (int i = 0; i < users; i++) {
			userIds.add("burst-" + runId + "-u" + i);
		}
		long t0 = System.nanoTime();
		Map<String, String> tokens = mintAll(userIds);
		System.out.printf("minted %d user tokens in %d ms%n", tokens.size(), (System.nanoTime() - t0) / 1_000_000);

		// 2. A fresh show: rows A-J x 100 seats, 25000 paise, limit 4.
		List<String> seats = new ArrayList<>();
		for (char row = 'A'; row <= 'J'; row++) {
			for (int n = 1; n <= 100; n++) {
				seats.add(row + Integer.toString(n));
			}
		}
		Res created = send("POST", "/shows", admin, showJson("burst-" + runId, seats, 25_000, 4), Map.of());
		check(created.status == 201, "show created (got " + created.status
				+ (created.status == 201 ? "" : " " + created.body) + ")");
		String showId = str(created.body, "id");
		System.out.println("show " + showId + " with " + seats.size() + " seats");
		Map<String, Integer> metricsBefore = scrapeShowMetrics(showId);

		Outcomes all = new Outcomes();

		// 3. Hot-seat storm: many users, one seat, all at once.
		banner("Hot-seat storm: " + hotStormUsers + " users -> A12");
		List<Send> storm = new ArrayList<>();
		for (int i = 0; i < hotStormUsers; i++) {
			storm.add(new Send(userIds.get(i), UUID.randomUUID().toString(), List.of("A12")));
		}
		Outcomes stormOut = fire(showId, storm, tokens);
		stormOut.print();
		all.addAll(stormOut);
		check(stormOut.count("201") == 1, "hot seat A12 has exactly one 201 (got " + stormOut.count("201") + ")");
		check(stormOut.count("409 SEAT_TAKEN") == hotStormUsers - 1,
				"every other A12 request is 409 SEAT_TAKEN (got " + stormOut.count("409 SEAT_TAKEN") + ")");

		// 4. Stampede: 80% at a few hot seats, 10% retried with the same key, 2% key reused.
		// Row A: the hot-seat storm. B1..B10: stampede hot seats. Rows C-I: the stampede's other 20%.
		// Row J is kept out of the stampede for the limit storm and spoof checks, which need free seats.
		List<String> hot = seats.subList(100, 100 + hotSeats); // B1..B10
		List<String> cold = seats.subList(200, 900); // C1..I100
		banner("Stampede: ~" + requests + " requests, 80% at " + hot.get(0) + ".." + hot.get(hot.size() - 1));
		List<Send> stampede = new ArrayList<>();
		int logical = (int) Math.round(requests / 1.12);
		for (int i = 0; i < logical; i++) {
			String user = userIds.get(random.nextInt(users));
			String seat = random.nextInt(10) < 8 ? hot.get(random.nextInt(hot.size()))
					: cold.get(random.nextInt(cold.size()));
			Send original = new Send(user, UUID.randomUUID().toString(), List.of(seat));
			stampede.add(original);
			if (random.nextInt(100) < 10) {
				stampede.add(original); // a retry: same user, same key, same body
			}
			if (random.nextInt(100) < 2) {
				String other = cold.get(random.nextInt(cold.size()));
				stampede.add(new Send(user, original.key, List.of(other.equals(seat) ? hot.get(0) : other)));
			}
		}
		Collections.shuffle(stampede, random);
		InvariantPoller poller = new InvariantPoller(showId, tokens.get(userIds.get(0)));
		Thread pollerThread = Thread.ofVirtual().start(poller);
		long stampedeStart = System.nanoTime();
		Outcomes stampedeOut = fire(showId, stampede, tokens);
		long stampedeMs = (System.nanoTime() - stampedeStart) / 1_000_000;
		poller.stop.set(true);
		pollerThread.join();
		stampedeOut.print();
		System.out.printf("%d requests in %d ms (%.0f req/s)%n", stampede.size(), stampedeMs,
				stampede.size() * 1000.0 / Math.max(1, stampedeMs));
		System.out.printf("invariant polled %d times during the stampede, %d violations%n", poller.samples.get(),
				poller.violations.get());
		all.addAll(stampedeOut);
		check(poller.violations.get() == 0, "invariant held on every poll during the stampede");
		check(poller.samples.get() > 0, "invariant was polled during the stampede");

		// 5. Limit storm: one fresh user, 10 parallel single-seat requests on a limit-4 show.
		banner("Limit storm: 1 user x 10 parallel requests, limit 4");
		String limitUser = "burst-" + runId + "-limit";
		tokens.put(limitUser, mint(limitUser, "USER"));
		List<Send> limitStorm = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			limitStorm.add(new Send(limitUser, UUID.randomUUID().toString(), List.of("J" + (91 + i))));
		}
		Outcomes limitOut = fire(showId, limitStorm, tokens);
		limitOut.print();
		all.addAll(limitOut);
		check(limitOut.count("201") == 4, "limit user got exactly 4 seats (got " + limitOut.count("201") + ")");
		check(limitOut.count("409 PER_USER_LIMIT") == 6, "the other 6 are 409 PER_USER_LIMIT");

		// 6. Spoof checks: identity always comes from the token.
		banner("Spoof checks");
		String victim = "burst-" + runId + "-victim";
		String attacker = "burst-" + runId + "-attacker";
		String victimToken = mint(victim, "USER");
		String attackerToken = mint(attacker, "USER");
		Res victimRes = send("POST", "/shows/" + showId + "/reserve", victimToken,
				reserveJson(List.of("J1"), UUID.randomUUID().toString(), null), Map.of());
		check(victimRes.status == 201, "victim books J1");
		String victimReservation = str(victimRes.body, "reservation_id");
		Res spoofed = send("POST", "/shows/" + showId + "/reserve", attackerToken,
				reserveJson(List.of("J2"), UUID.randomUUID().toString(), victim), Map.of());
		check(spoofed.status == 201 && attacker.equals(str(spoofed.body, "user_id")),
				"body user_id is ignored: booking belongs to the token's user");
		Res stealCancel = send("POST", "/reservations/" + victimReservation + "/cancel", attackerToken, "{}", Map.of());
		check(stealCancel.status == 404, "attacker can't cancel the victim's reservation (got " + stealCancel.status + ")");
		Res stealRead = send("GET", "/reservations/" + victimReservation, attackerToken, null, Map.of());
		check(stealRead.status == 404, "attacker can't read the victim's reservation (got " + stealRead.status + ")");
		Res victimAfter = send("GET", "/reservations/" + victimReservation, victimToken, null, Map.of());
		check("confirmed".equals(str(victimAfter.body, "status")), "victim's reservation is still confirmed");
		System.out.println("spoof checks done");

		// 7. Outcomes and latency.
		banner("All reserve requests");
		all.print();
		printLatency();

		// 8. Reconciliation against the API and the metrics.
		banner("Reconciliation");
		Thread.sleep(2_500); // the seat gauges refresh every second
		Res state = send("GET", "/shows/" + showId, admin, null, Map.of());
		int total = num(state.body, "total_seats");
		int available = num(state.body, "available");
		int held = num(state.body, "held");
		int confirmed = num(state.body, "confirmed");
		System.out.printf("GET /shows: total=%d available=%d held=%d confirmed=%d%n", total, available, held,
				confirmed);
		check(available + held + confirmed == total, "available + held + confirmed == total_seats");

		// Seats taken by this run: every 201, plus the two spoof-check bookings.
		List<String> bookedSeats = new ArrayList<>(all.bookedSeats);
		bookedSeats.add("J1");
		bookedSeats.add("J2");
		check(new HashSet<>(bookedSeats).size() == bookedSeats.size(), "no seat appears in two 201 responses");
		check(confirmed == bookedSeats.size(),
				"API confirmed (" + confirmed + ") == seats in 201 responses (" + bookedSeats.size() + ")");
		List<String> oversold = hot.stream()
			.filter(seat -> all.bookedSeats.stream().filter(seat::equals).count() > 1)
			.toList();
		long hotWinners = hot.stream().filter(all.bookedSeats::contains).count();
		check(oversold.isEmpty(), "each stampede hot seat has at most one winner (" + hotWinners + " of " + hot.size()
				+ " sold" + (oversold.isEmpty() ? "" : "; oversold: " + oversold) + ")");
		check(all.replaysPointAtCreated(), "every 200 replays a reservation some 201 created");
		check(all.count5xx() == 0, "zero 5xx responses (got " + all.count5xx() + ")");
		check(all.count("transport error") == 0, "zero transport errors (got " + all.count("transport error") + ")");

		Map<String, Integer> metrics = scrapeShowMetrics(showId);
		if (metrics.isEmpty()) {
			failures.add("could not read bookify_* metrics from /actuator/prometheus");
		}
		else {
			int created201 = all.count("201") + 2; // + spoof-check bookings
			reconcile(metrics, metricsBefore, "confirmed", created201);
			reconcile(metrics, metricsBefore, "seats_confirmed", bookedSeats.size());
			reconcile(metrics, metricsBefore, "replayed", all.count("200"));
			reconcile(metrics, metricsBefore, "declined:seat_taken", all.count("409 SEAT_TAKEN"));
			reconcile(metrics, metricsBefore, "declined:per_user_limit", all.count("409 PER_USER_LIMIT"));
			reconcile(metrics, metricsBefore, "declined:idempotency_mismatch",
					all.count("409 IDEMPOTENCY_KEY_REUSED"));
			reconcile(metrics, metricsBefore, "declined:contention", all.count("409 CONTENTION"));
			reconcile(metrics, metricsBefore, "gauge:confirmed", confirmed);
			check(metrics.getOrDefault("gauge:available", -1) + metrics.getOrDefault("gauge:held", -1)
					+ metrics.getOrDefault("gauge:confirmed", -1) == metrics.getOrDefault("gauge:total", -2),
					"metrics: sum of bookify_seats == bookify_show_seats");
		}

		banner(failures.isEmpty() ? "PASS" : "FAIL (" + failures.size() + ")");
		failures.forEach(f -> System.out.println("  x " + f));
		System.exit(failures.isEmpty() ? 0 : 1);
	}

	// ---- firing requests -----------------------------------------------------------------------

	record Send(String user, String key, List<String> seats) {
	}

	/** Sends every request at once (bounded by the in-flight limit) and tallies the outcomes. */
	static Outcomes fire(String showId, List<Send> sends, Map<String, String> tokens) throws Exception {
		Outcomes out = new Outcomes();
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Future<?>> futures = new ArrayList<>();
			for (Send s : sends) {
				futures.add(pool.submit(() -> {
					start.await();
					// Latency covers the HTTP request only, not the wait for one of our in-flight slots.
					inFlight.acquire();
					try {
						long t = System.nanoTime();
						Res res = sendDirect("POST", "/shows/" + showId + "/reserve", tokens.get(s.user),
								reserveJson(s.seats, s.key, null), Map.of());
						reserveLatenciesMicros.add((System.nanoTime() - t) / 1_000);
						out.record(s, res);
					}
					finally {
						inFlight.release();
					}
					return null;
				}));
			}
			start.countDown();
			for (Future<?> f : futures) {
				f.get();
			}
		}
		return out;
	}

	/** Thread-safe tally of reserve outcomes, plus what each 201/200 said. */
	static class Outcomes {

		final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();

		final List<String> bookedSeats = Collections.synchronizedList(new ArrayList<>());

		final Set<String> createdIds = ConcurrentHashMap.newKeySet();

		final Set<String> replayedIds = ConcurrentHashMap.newKeySet();

		final List<String> transportErrors = Collections.synchronizedList(new ArrayList<>());

		void record(Send s, Res res) {
			String label;
			if (res.status < 0) {
				label = "transport error";
				if (transportErrors.size() < 5) {
					transportErrors.add(res.body);
				}
			}
			else if (res.status == 201) {
				label = "201";
				bookedSeats.addAll(s.seats);
				createdIds.add(str(res.body, "reservation_id"));
			}
			else if (res.status == 200) {
				label = "200";
				replayedIds.add(str(res.body, "reservation_id"));
			}
			else if (res.status >= 400 && res.status < 500) {
				String code = str(res.body, "code");
				label = res.status + " " + (code == null ? "?" : code);
			}
			else {
				label = Integer.toString(res.status);
			}
			counts.computeIfAbsent(label, k -> new AtomicInteger()).incrementAndGet();
		}

		void addAll(Outcomes other) {
			other.counts.forEach((k, v) -> counts.computeIfAbsent(k, x -> new AtomicInteger()).addAndGet(v.get()));
			bookedSeats.addAll(other.bookedSeats);
			createdIds.addAll(other.createdIds);
			replayedIds.addAll(other.replayedIds);
			transportErrors.addAll(other.transportErrors);
		}

		int count(String label) {
			AtomicInteger c = counts.get(label);
			return c == null ? 0 : c.get();
		}

		int count5xx() {
			return counts.entrySet()
				.stream()
				.filter(e -> e.getKey().startsWith("5"))
				.mapToInt(e -> e.getValue().get())
				.sum();
		}

		boolean replaysPointAtCreated() {
			return createdIds.containsAll(replayedIds);
		}

		void print() {
			int total = counts.values().stream().mapToInt(AtomicInteger::get).sum();
			Map<String, String> meaning = Map.of("201", "confirmed (new booking)", "200",
					"idempotent replay (same key retried)");
			new TreeMap<>(counts).forEach((label, n) -> System.out.printf("  %-28s %7d  %5.1f%%  %s%n", label,
					n.get(), 100.0 * n.get() / Math.max(1, total), meaning.getOrDefault(label, "")));
			System.out.printf("  %-28s %7d%n", "total", total);
			transportErrors.forEach(e -> System.out.println("  transport error: " + e));
		}

	}

	/** Polls GET /shows/{id} every 200ms and counts snapshots where the counts don't add up. */
	static class InvariantPoller implements Runnable {

		final String showId;

		final String token;

		final AtomicBoolean stop = new AtomicBoolean();

		final AtomicInteger samples = new AtomicInteger();

		final AtomicInteger violations = new AtomicInteger();

		InvariantPoller(String showId, String token) {
			this.showId = showId;
			this.token = token;
		}

		@Override
		public void run() {
			while (!stop.get()) {
				Res res = sendDirect("GET", "/shows/" + showId, token, null, Map.of());
				if (res.status == 200) {
					samples.incrementAndGet();
					int sum = num(res.body, "available") + num(res.body, "held") + num(res.body, "confirmed");
					if (sum != num(res.body, "total_seats")) {
						violations.incrementAndGet();
					}
				}
				try {
					Thread.sleep(200);
				}
				catch (InterruptedException ex) {
					return;
				}
			}
		}

	}

	// ---- HTTP ----------------------------------------------------------------------------------

	record Res(int status, String body) {
	}

	/** Sends one request, waiting for an in-flight slot first. Status -1 means a transport error. */
	static Res send(String method, String path, String token, String json, Map<String, String> headers) {
		try {
			inFlight.acquire();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return new Res(-1, ex.toString());
		}
		try {
			return sendDirect(method, path, token, json, headers);
		}
		finally {
			inFlight.release();
		}
	}

	/** Sends without taking an in-flight slot (the invariant poller must not queue behind the burst). */
	static Res sendDirect(String method, String path, String token, String json, Map<String, String> headers) {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(120));
		if (token != null) {
			b.header("Authorization", "Bearer " + token);
		}
		headers.forEach(b::header);
		if (json != null) {
			b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
		}
		else {
			b.method(method, HttpRequest.BodyPublishers.noBody());
		}
		try {
			HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
			return new Res(r.statusCode(), r.body());
		}
		catch (IOException ex) {
			return new Res(-1, ex.toString());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return new Res(-1, ex.toString());
		}
	}

	static String mint(String userId, String role) {
		Map<String, String> headers = "ADMIN".equals(role) ? Map.of("X-Admin-Key", adminKey) : Map.of();
		Res res = send("POST", "/auth/token", null,
				"{\"user_id\":\"" + userId + "\",\"role\":\"" + role + "\"}", headers);
		if (res.status != 200) {
			System.err.println("Could not mint a " + role + " token: " + res.status + " " + res.body);
			if ("ADMIN".equals(role)) {
				System.err.println("Pass the admin key with --admin-key or BOOKIFY_ADMIN_KEY.");
			}
			System.exit(2);
		}
		return str(res.body, "token");
	}

	static Map<String, String> mintAll(List<String> userIds) throws Exception {
		Map<String, String> tokens = new ConcurrentHashMap<>();
		try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Future<?>> futures = new ArrayList<>();
			for (String id : userIds) {
				futures.add(pool.submit(() -> tokens.put(id, mint(id, "USER"))));
			}
			for (Future<?> f : futures) {
				f.get();
			}
		}
		return tokens;
	}

	// ---- metrics -------------------------------------------------------------------------------

	static final Pattern METRIC = Pattern.compile("^(bookify_[a-z_]+)\\{([^}]*)\\}\\s+([0-9.eE+-]+)$");

	/** This show's bookify_* metrics, keyed like "confirmed", "declined:seat_taken", "gauge:held". */
	static Map<String, Integer> scrapeShowMetrics(String showId) {
		Res res = sendDirect("GET", "/actuator/prometheus", null, null, Map.of());
		Map<String, Integer> out = new HashMap<>();
		if (res.status != 200) {
			return out;
		}
		for (String line : res.body.split("\n")) {
			Matcher m = METRIC.matcher(line.trim());
			if (!m.matches() || !m.group(2).contains("show=\"" + showId + "\"")) {
				continue;
			}
			String name = m.group(1);
			int value = (int) Math.round(Double.parseDouble(m.group(3)));
			String key = switch (name) {
				case "bookify_reservations_confirmed_total" -> "confirmed";
				case "bookify_seats_confirmed_total" -> "seats_confirmed";
				case "bookify_reservations_replayed_total" -> "replayed";
				case "bookify_reservations_declined_total" -> "declined:" + label(m.group(2), "reason");
				case "bookify_seats" -> "gauge:" + label(m.group(2), "status");
				case "bookify_show_seats" -> "gauge:total";
				default -> null;
			};
			if (key != null) {
				out.merge(key, value, Integer::sum);
			}
		}
		return out;
	}

	static void reconcile(Map<String, Integer> after, Map<String, Integer> before, String key, int expected) {
		int delta = after.getOrDefault(key, 0) - (key.startsWith("gauge:") ? 0 : before.getOrDefault(key, 0));
		boolean ok = delta == expected;
		System.out.printf("  %-34s metric %6d   responses %6d   %s%n", key, delta, expected, ok ? "ok" : "MISMATCH");
		if (!ok) {
			failures.add("metrics: " + key + " = " + delta + ", responses say " + expected);
		}
	}

	static String label(String labels, String name) {
		Matcher m = Pattern.compile(name + "=\"([^\"]*)\"").matcher(labels);
		return m.find() ? m.group(1) : "";
	}

	// ---- output and checks ---------------------------------------------------------------------

	static void printLatency() {
		List<Long> l = new ArrayList<>(reserveLatenciesMicros);
		if (l.isEmpty()) {
			return;
		}
		Collections.sort(l);
		System.out.printf("reserve latency (per request, at --concurrency in flight): p50=%s p95=%s p99=%s max=%s (n=%d)%n", ms(pct(l, 50)), ms(pct(l, 95)),
				ms(pct(l, 99)), ms(l.get(l.size() - 1)), l.size());
	}

	static long pct(List<Long> sorted, int p) {
		return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p / 100.0 * sorted.size()) - 1));
	}

	static String ms(long micros) {
		return String.format("%.1fms", micros / 1000.0);
	}

	static void check(boolean ok, String what) {
		System.out.println((ok ? "  ok  " : "  FAIL ") + what);
		if (!ok) {
			failures.add(what);
		}
	}

	static void banner(String title) {
		System.out.println();
		System.out.println("== " + title + " " + "=".repeat(Math.max(0, 70 - title.length())));
	}

	// ---- tiny JSON helpers (responses are flat, known shapes) ----------------------------------

	static String str(String json, String field) {
		if (json == null) {
			return null;
		}
		Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
		return m.find() ? m.group(1) : null;
	}

	static int num(String json, String field) {
		if (json == null) {
			return -1;
		}
		Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*(-?\\d+)").matcher(json);
		return m.find() ? Integer.parseInt(m.group(1)) : -1;
	}

	static String showJson(String name, List<String> seats, long price, int limit) {
		return "{\"name\":\"" + name + "\",\"price_paise\":" + price + ",\"per_user_limit\":" + limit + ",\"seats\":"
				+ jsonArray(seats) + "}";
	}

	static String reserveJson(List<String> seats, String key, String spoofedUserId) {
		return "{\"seats\":" + jsonArray(seats) + ",\"idempotency_key\":\"" + key + "\""
				+ (spoofedUserId == null ? "" : ",\"user_id\":\"" + spoofedUserId + "\"") + "}";
	}

	static String jsonArray(List<String> values) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < values.size(); i++) {
			sb.append(i == 0 ? "" : ",").append('"').append(values.get(i)).append('"');
		}
		return sb.append(']').toString();
	}

	// ---- args ----------------------------------------------------------------------------------

	static void parseArgs(String[] args) {
		List<String> rest = new ArrayList<>();
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--admin-key" -> adminKey = args[++i];
				case "--requests" -> requests = Integer.parseInt(args[++i]);
				case "--users" -> users = Integer.parseInt(args[++i]);
				case "--hot-storm" -> hotStormUsers = Integer.parseInt(args[++i]);
				case "--concurrency" -> concurrency = Integer.parseInt(args[++i]);
				case "--seed" -> seed = Long.parseLong(args[++i]);
				case "-h", "--help" -> usage(0);
				default -> rest.add(args[i]);
			}
		}
		if (rest.size() != 1) {
			usage(2);
		}
		baseUrl = rest.get(0).replaceAll("/+$", "");
		if (hotStormUsers > users) {
			users = hotStormUsers;
		}
	}

	static void usage(int code) {
		System.out.println("""
				Usage: ./burst.sh <BASE_URL> [options]

				  --admin-key KEY     admin key for minting the admin token (default: $BOOKIFY_ADMIN_KEY,
				                      else the local compose default)
				  --requests N        stampede size (default 20000)
				  --users N           distinct users (default 2000)
				  --hot-storm N       users racing for one seat (default 500)
				  --concurrency N     max requests in flight (default 1000)
				  --seed N            random seed, to repeat a run
				""");
		System.exit(code);
	}

	static String envOr(String name, String fallback) {
		String v = System.getenv(name);
		return v == null || v.isBlank() ? fallback : v;
	}

}
