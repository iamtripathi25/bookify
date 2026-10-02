package com.iamtripathi25.bookify.show;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.iamtripathi25.bookify.error.InvalidRequestException;
import com.iamtripathi25.bookify.error.NotFoundException;
import com.iamtripathi25.bookify.show.ShowRepository.SeatView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShowService {

	static final int DEFAULT_PER_USER_LIMIT = 4;

	private final ShowRepository repository;

	/** Shows never change after creation, so a hit can be served forever. Misses aren't cached. */
	private final Map<UUID, Show> cache = new ConcurrentHashMap<>();

	public ShowService(ShowRepository repository) {
		this.repository = repository;
	}

	@Transactional
	public ShowState create(String name, List<String> seats, long pricePaise, Integer perUserLimit) {
		rejectDuplicates(seats);
		Show show = new Show(UUID.randomUUID(), name, pricePaise,
				perUserLimit == null ? DEFAULT_PER_USER_LIMIT : perUserLimit, seats.size());
		repository.insert(show, seats);
		List<SeatView> views = seats.stream().map(l -> new SeatView(l, SeatStatus.AVAILABLE)).toList();
		return ShowState.of(show, views);
	}

	public Show get(UUID id) {
		Show cached = cache.get(id);
		if (cached != null) {
			return cached;
		}
		Show show = repository.findById(id).orElseThrow(() -> new NotFoundException("Show not found"));
		cache.put(id, show);
		return show;
	}

	/** Seat list and counts come from the same single SELECT, so they can never disagree. */
	public ShowState state(UUID id) {
		Show show = get(id);
		return ShowState.of(show, repository.findSeats(id));
	}

	private static void rejectDuplicates(List<String> seats) {
		Set<String> seen = new HashSet<>();
		Set<String> duplicates = new LinkedHashSet<>();
		for (String seat : seats) {
			if (!seen.add(seat)) {
				duplicates.add(seat);
			}
		}
		if (!duplicates.isEmpty()) {
			throw new InvalidRequestException("Duplicate seats: " + String.join(", ", new ArrayList<>(duplicates)));
		}
	}

}
