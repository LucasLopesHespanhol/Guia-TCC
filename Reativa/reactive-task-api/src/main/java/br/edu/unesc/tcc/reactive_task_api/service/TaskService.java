package br.edu.unesc.tcc.reactive_task_api.service;

import br.edu.unesc.tcc.reactive_task_api.domain.Task;
import br.edu.unesc.tcc.reactive_task_api.dto.TaskRequest;
import br.edu.unesc.tcc.reactive_task_api.dto.TaskResponse;
import br.edu.unesc.tcc.reactive_task_api.exception.TaskNotFoundException;
import br.edu.unesc.tcc.reactive_task_api.mapper.TaskMapper;
import br.edu.unesc.tcc.reactive_task_api.repository.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.util.List;

@Service
public class TaskService {

    private final TaskRepository repository;
    private final TransactionalOperator transactionalOperator;

    public TaskService(
            TaskRepository repository,
            ReactiveTransactionManager transactionManager
    ) {
        this.repository = repository;
        this.transactionalOperator =
                TransactionalOperator.create(transactionManager);
    }

    public Mono<TaskResponse> create(TaskRequest request) {
        Mono<TaskResponse> operation = Mono.defer(
                () -> repository.save(TaskMapper.toTask(request))
        ).map(TaskMapper::toResponse);

        return afterCommit(operation);
    }

    public Mono<TaskResponse> findById(Long id) {
        return findTask(id)
                .map(TaskMapper::toResponse);
    }

    public Mono<List<TaskResponse>> findAll() {
        return repository.findAllByOrderByIdAsc()
                .map(TaskMapper::toResponse)
                .collectList();
    }

    public Mono<TaskResponse> update(Long id, TaskRequest request) {
        Mono<TaskResponse> operation = findTask(id)
                .flatMap(task -> {
                    TaskMapper.updateTask(task, request);
                    return repository.save(task);
                })
                .map(TaskMapper::toResponse);

        return afterCommit(operation);
    }

    public Mono<Void> delete(Long id) {
        Mono<Void> operation = findTask(id)
                .flatMap(task -> repository.deleteById(task.getId()));

        return transactionalOperator
                .transactional(operation.flux())
                .then();
    }

    private Mono<Task> findTask(Long id) {
        return repository.findById(id)
                .switchIfEmpty(
                        Mono.error(new TaskNotFoundException(id))
                );
    }

    private <T> Mono<T> afterCommit(Mono<T> operation) {
        return transactionalOperator
                .transactional(operation.flux())
                .single();
    }
}