package com.iamtripathi25.bookify.show;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/shows")
public class ShowController {

	static final int MAX_SEATS = 10_000;

	private final ShowService shows;

	public ShowController(ShowService shows) {
		this.shows = shows;
	}

	@PostMapping
	ResponseEntity<ShowState> create(@Valid @RequestBody CreateShowRequest request) {
		ShowState created = shows.create(request.name(), request.seats(), request.pricePaise(),
				request.perUserLimit());
		return ResponseEntity.created(URI.create("/shows/" + created.id())).body(created);
	}

	@GetMapping("/{id}")
	ShowState get(@PathVariable UUID id) {
		return shows.state(id);
	}

	record CreateShowRequest(
			@NotBlank @Size(max = 200) String name,
			@NotEmpty @Size(max = MAX_SEATS) List<@NotBlank @Size(max = 32) String> seats,
			@NotNull @Min(0) Long pricePaise,
			@Min(1) Integer perUserLimit) {
	}

}
