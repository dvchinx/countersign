package io.github.dvchinx.countersign.autoconfigure;

import io.github.dvchinx.countersign.audit.jdbc.JdbcAuditSink;
import io.github.dvchinx.countersign.core.spi.AuditSink;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Stores receipts in PostgreSQL when the application adds {@code countersign-audit-jdbc} and has one
 * {@link DataSource}.
 *
 * <p>Adding the module is the opt-in: no property is needed. It runs before {@link CountersignAutoConfiguration}, so
 * the in-memory sink backs off. An {@link AuditSink} bean defined by the application still wins. Unless
 * {@code countersign.audit.jdbc.initialize-schema=false}, the table is created at startup if it is missing; a failure
 * there stops the application rather than leaving receipts with nowhere to go. See ADR 0010.
 */
@AutoConfiguration(
        before = CountersignAutoConfiguration.class,
        afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@ConditionalOnClass(name = "io.github.dvchinx.countersign.audit.jdbc.JdbcAuditSink")
@ConditionalOnBooleanProperty(name = "countersign.enabled", matchIfMissing = true)
@ConditionalOnSingleCandidate(DataSource.class)
@EnableConfigurationProperties(CountersignProperties.class)
public class CountersignJdbcAuditAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(AuditSink.class)
    JdbcAuditSink countersignJdbcAuditSink(DataSource dataSource, CountersignProperties properties) {
        if (properties.getAudit().getJdbc().isInitializeSchema()) {
            JdbcAuditSink.createSchema(dataSource);
        }
        return new JdbcAuditSink(dataSource);
    }
}
