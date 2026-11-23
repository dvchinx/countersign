package io.github.dvchinx.countersign.audit.jdbc;

import com.zaxxer.hikari.HikariDataSource;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** One PostgreSQL container for the whole test run, with a fresh schema for each test that wants one. */
final class TestDatabase {

    private static final PostgreSQLContainer POSTGRES = start();

    private TestDatabase() {}

    private static PostgreSQLContainer start() {
        PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");
        postgres.start();
        return postgres;
    }

    /**
     * A small connection pool whose search path is a new, empty schema. Its user is a superuser, as in the container,
     * so tests can step past the append-only triggers.
     */
    static DataSource freshSchema() {
        String schema =
                "t_" + HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextLong());
        new JdbcTemplate(simple(null)).execute("CREATE SCHEMA " + schema);
        HikariDataSource pool = new HikariDataSource();
        pool.setDataSource(simple(schema));
        pool.setMaximumPoolSize(8);
        return pool;
    }

    /** A data source pointing at a port where nothing listens. */
    static DataSource unreachable() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL("jdbc:postgresql://127.0.0.1:1/countersign");
        dataSource.setConnectTimeout(1);
        return dataSource;
    }

    private static PGSimpleDataSource simple(@Nullable String schema) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        if (schema != null) {
            dataSource.setCurrentSchema(schema);
        }
        return dataSource;
    }
}
