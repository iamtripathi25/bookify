package com.iamtripathi25.bookify;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class BookifyApplicationTests {

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void contextLoads() {
	}

	@Test
	void mainPoolConnectionsCarryLockAndStatementTimeouts() {
		assertThat(jdbc.queryForObject("SHOW lock_timeout", String.class)).isEqualTo("3s");
		assertThat(jdbc.queryForObject("SHOW statement_timeout", String.class)).isEqualTo("30s");
	}

}
