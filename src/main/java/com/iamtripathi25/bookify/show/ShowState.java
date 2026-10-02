package com.iamtripathi25.bookify.show;

import java.util.List;
import java.util.UUID;

import com.iamtripathi25.bookify.show.ShowRepository.SeatView;

/** GET /shows/{id} body. available + held + confirmed == total_seats by construction. */
public record ShowState(UUID id, String name, long pricePaise, int perUserLimit, int totalSeats, Counts counts,
		List<SeatView> seats) {

	public static ShowState of(Show show, List<SeatView> seats) {
		int available = 0;
		int held = 0;
		int confirmed = 0;
		for (SeatView seat : seats) {
			switch (seat.status()) {
				case AVAILABLE -> available++;
				case HELD -> held++;
				case CONFIRMED -> confirmed++;
			}
		}
		return new ShowState(show.id(), show.name(), show.pricePaise(), show.perUserLimit(), show.totalSeats(),
				new Counts(available, held, confirmed), seats);
	}

	public record Counts(int available, int held, int confirmed) {
	}

}
