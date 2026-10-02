package com.iamtripathi25.bookify.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;

import org.springframework.jdbc.core.support.AbstractSqlTypeValue;

/**
 * Binds a Postgres {@code text[]} parameter. Passing a raw collection to
 * NamedParameterJdbcTemplate would expand it into {@code ?, ?, ?} instead.
 */
public final class PgArrays {

	private PgArrays() {
	}

	public static AbstractSqlTypeValue text(Collection<String> values) {
		String[] array = values.toArray(String[]::new);
		return new AbstractSqlTypeValue() {
			@Override
			protected Object createTypeValue(Connection con, int sqlType, String typeName) throws SQLException {
				return con.createArrayOf("text", array);
			}
		};
	}

}
