
package br.edu.unesc.tcc.reactive_task_api.messaging;

import br.edu.unesc.tcc.reactive_task_api.dto.TaskRequest;

import java.util.UUID;

public record TaskCommand(
        UUID commandId,
        TaskOperation operation,
        Long taskId,
        TaskRequest request
) {
}