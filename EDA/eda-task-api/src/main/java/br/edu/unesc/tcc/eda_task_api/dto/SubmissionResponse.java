package br.edu.unesc.tcc.eda_task_api.dto;

import br.edu.unesc.tcc.eda_task_api.domain.SubmissionStatus;
import br.edu.unesc.tcc.eda_task_api.events.TaskEventType;

import java.time.Instant;
import java.util.UUID;

public record SubmissionResponse(
        UUID id,
        TaskEventType tipo,
        SubmissionStatus estado,
        Long tarefaId,
        TaskResponse resultado,
        String erro,
        Instant registradaEm,
        Instant finalizadaEm
) {
}
