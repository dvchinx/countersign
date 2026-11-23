package io.github.dvchinx.countersign.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dvchinx.countersign.audit.jdbc.JdbcAuditSink;
import io.github.dvchinx.countersign.core.audit.InMemoryAuditSink;
import io.github.dvchinx.countersign.core.guard.ToolCallGuard;
import io.github.dvchinx.countersign.core.guard.Verdict;
import io.github.dvchinx.countersign.core.model.JsonObject;
import io.github.dvchinx.countersign.core.model.ToolInvocation;
import io.github.dvchinx.countersign.core.receipt.ReceiptChain;
import io.github.dvchinx.countersign.core.spi.AuditSink;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@ExtendWith(OutputCaptureExtension.class)
class JdbcAuditAutoConfigurationTest {

    private static final PostgreSQLContainer POSTGRES = startPostgres();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    CountersignJdbcAuditAutoConfiguration.class,
                    CountersignAutoConfiguration.class))
            .withPropertyValues(
                    "countersign.policies[0].id=balances",
                    "countersign.policies[0].tools=accounts.balance",
                    "countersign.policies[0].decision=allow");

    private static PostgreSQLContainer startPostgres() {
        PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");
        postgres.start();
        return postgres;
    }

    /** Points the application at a new, empty schema, so each test starts without the receipts table. */
    private static String[] freshDatabase() {
        String schema =
                "t_" + HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextLong());
        admin().execute("CREATE SCHEMA " + schema);
        return new String[] {
            "spring.datasource.url=" + POSTGRES.getJdbcUrl() + "&currentSchema=" + schema,
            "spring.datasource.username=" + POSTGRES.getUsername(),
            "spring.datasource.password=" + POSTGRES.getPassword()
        };
    }

    private static JdbcTemplate admin() {
        return new JdbcTemplate(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    private static Verdict checkBalance(ToolCallGuard guard) {
        return guard.check(ToolInvocation.of(
                "accounts.balance", JsonObject.builder().put("account", "A-1").build()));
    }

    @Test
    void storesReceiptsInPostgresqlWhenADataSourceExists(CapturedOutput output) {
        runner.withPropertyValues(freshDatabase()).run(context -> {
            assertThat(context).hasSingleBean(AuditSink.class).hasSingleBean(JdbcAuditSink.class);

            Verdict verdict = checkBalance(context.getBean(ToolCallGuard.class));

            JdbcAuditSink sink = context.getBean(JdbcAuditSink.class);
            String streamId = context.getBean(ReceiptChain.class).streamId();
            assertThat(verdict)
                    .isInstanceOfSatisfying(
                            Verdict.Proceed.class,
                            proceed -> assertThat(sink.stream(streamId)).containsExactly(proceed.receipt()));
            assertThat(output).contains("audit sink JdbcAuditSink").doesNotContain("receipts in memory only");
        });
    }

    @Test
    void leavesTheSchemaAloneWhenAskedTo() {
        runner.withPropertyValues(freshDatabase())
                .withPropertyValues("countersign.audit.jdbc.initialize-schema=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(JdbcAuditSink.class);
                    JdbcTemplate sql = new JdbcTemplate(context.getBean(DataSource.class));
                    assertThat(sql.queryForObject("SELECT to_regclass('countersign_receipt') IS NULL", Boolean.class))
                            .isTrue();
                });
    }

    @Test
    void refusesAnAllowedCallWhenTheTableIsMissingAndAuditIsStrict() {
        runner.withPropertyValues(freshDatabase())
                .withPropertyValues("countersign.audit.jdbc.initialize-schema=false", "countersign.audit.strict=true")
                .run(context -> assertThat(checkBalance(context.getBean(ToolCallGuard.class)))
                        .isInstanceOf(Verdict.Refuse.class));
    }

    @Test
    void keepsReceiptsInMemoryWithoutADataSource() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        CountersignJdbcAuditAutoConfiguration.class, CountersignAutoConfiguration.class))
                .run(context -> assertThat(context.getBean(AuditSink.class)).isInstanceOf(InMemoryAuditSink.class));
    }

    @Test
    void keepsReceiptsInMemoryWhenTheJdbcModuleIsNotOnTheClasspath() {
        runner.withPropertyValues(freshDatabase())
                .withClassLoader(new FilteredClassLoader(JdbcAuditSink.class))
                .run(context -> assertThat(context.getBean(AuditSink.class)).isInstanceOf(InMemoryAuditSink.class));
    }

    @Test
    void backsOffWhenTheApplicationDefinesAnAuditSink() {
        runner.withPropertyValues(freshDatabase())
                .withUserConfiguration(OwnAuditSink.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(JdbcAuditSink.class);
                    assertThat(context.getBean(AuditSink.class)).isSameAs(OwnAuditSink.SINK);
                });
    }

    @Test
    void doesNothingWhenCountersignIsDisabled() {
        runner.withPropertyValues(freshDatabase())
                .withPropertyValues("countersign.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(AuditSink.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class OwnAuditSink {

        static final AuditSink SINK = receipt -> {};

        @Bean
        AuditSink ownAuditSink() {
            return SINK;
        }
    }
}
