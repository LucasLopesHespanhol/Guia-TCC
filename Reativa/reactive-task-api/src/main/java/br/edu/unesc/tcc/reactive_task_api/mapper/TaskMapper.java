package br.edu.unesc.tcc.reactive_task_api.mapper;

import br.edu.unesc.tcc.reactive_task_api.domain.Task;
import br.edu.unesc.tcc.reactive_task_api.dto.TaskRequest;
import br.edu.unesc.tcc.reactive_task_api.dto.TaskResponse;

public final class TaskMapper {

    private TaskMapper() {
    }

    public static Task toTask(TaskRequest request) {
        Task task = new Task();
        updateTask(task, request);
        return task;
    }

    public static TaskResponse toResponse(Task task) {
        return new TaskResponse(
                task.getId(),
                task.getTitulo(),
                task.getDescricao(),
                task.getPrioridade(),
                task.getStatus(),
                task.getDataLimite()
        );
    }

    public static void updateTask(Task task, TaskRequest request) {
        task.setTitulo(request.titulo());
        task.setDescricao(request.descricao());
        task.setPrioridade(request.prioridade());
        task.setStatus(request.status());
        task.setDataLimite(request.dataLimite());
    }
}