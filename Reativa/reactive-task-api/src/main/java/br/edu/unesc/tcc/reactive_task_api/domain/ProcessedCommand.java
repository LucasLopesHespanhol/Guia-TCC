package br.edu.unesc.tcc.reactive_task_api.domain;

import java.util.UUID;

public record ProcessedCommand(
        UUID commandId,
        String requestHash,
        String responseJson
) {
}