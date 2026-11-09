package br.edu.unesc.tcc.eda_task_api.events;

import java.time.Instant;
import java.util.UUID;

public record TaskEvent(
        UUID eventId,
        UUID submissionId,
        TaskEventType type,
        Instant occurredAt,
        Long taskId,
        int schemaVersion,
        String runId) {

    public void validate() {
        if (eventId == null || submissionId == null || type == null
                || occurredAt == null || schemaVersion != 1
                || runId == null || runId.isBlank() || runId.length() > 100) {
            throw new IllegalArgumentException("Evento incompleto ou versão não suportada");
        }
    }
}
