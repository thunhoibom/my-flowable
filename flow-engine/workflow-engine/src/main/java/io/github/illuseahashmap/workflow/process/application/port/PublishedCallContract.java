package io.github.illuseahashmap.workflow.process.application.port;

import java.util.List;

public record PublishedCallContract(
        List<ServiceInputContract> inputs,
        List<ServiceOutputContract> outputs,
        String callStyle,
        String correlationOutput,
        int waitTimeoutMillis) {

    public PublishedCallContract(List<ServiceInputContract> inputs, List<ServiceOutputContract> outputs) {
        this(inputs, outputs, "SYNC", null, 0);
    }
}
