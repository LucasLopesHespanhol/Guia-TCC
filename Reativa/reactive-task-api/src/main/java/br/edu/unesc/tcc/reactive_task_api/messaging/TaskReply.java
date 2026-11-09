package br.edu.unesc.tcc.reactive_task_api.messaging;

import br.edu.unesc.tcc.reactive_task_api.dto.TaskResponse;

import java.util.List;
import java.util.UUID;

public record TaskReply(
        UUID commandId,
        int status,
        TaskResponse task,
        List<TaskResponse> tasks,
        String detail
) {
}