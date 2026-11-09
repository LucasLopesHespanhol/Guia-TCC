package br.edu.unesc.tcc.reactive_task_api.exception;

public class TaskNotFoundException extends RuntimeException {

    public TaskNotFoundException(Long id) {
        super("Tarefa não encontrada: " + id);
    }
}