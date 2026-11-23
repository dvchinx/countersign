package io.github.dvchinx.countersign.test;

import io.github.dvchinx.countersign.core.audit.InMemoryAuditSink;
import io.github.dvchinx.countersign.core.model.Receipt;
import io.github.dvchinx.countersign.core.spi.AuditSink;
import java.util.List;

class InMemoryAuditSinkContractTest extends AuditSinkContract {

    private final InMemoryAuditSink sink = new InMemoryAuditSink();

    @Override
    protected AuditSink sink() {
        return sink;
    }

    @Override
    protected List<Receipt> storedStream(String streamId) {
        return sink.stream(streamId);
    }
}
