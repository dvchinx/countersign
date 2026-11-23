package io.github.dvchinx.countersign.audit.jdbc;

import io.github.dvchinx.countersign.core.model.Receipt;
import io.github.dvchinx.countersign.core.spi.AuditSink;
import io.github.dvchinx.countersign.test.AuditSinkContract;
import java.util.List;
import javax.sql.DataSource;

class JdbcAuditSinkContractTest extends AuditSinkContract {

    private static final JdbcAuditSink SINK = createSink();

    private static JdbcAuditSink createSink() {
        DataSource dataSource = TestDatabase.freshSchema();
        JdbcAuditSink.createSchema(dataSource);
        return new JdbcAuditSink(dataSource);
    }

    @Override
    protected AuditSink sink() {
        return SINK;
    }

    @Override
    protected List<Receipt> storedStream(String streamId) {
        return SINK.stream(streamId);
    }
}
