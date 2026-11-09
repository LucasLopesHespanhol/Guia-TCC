package br.edu.unesc.tcc.reactive_task_api.controller;

import br.edu.unesc.tcc.reactive_task_api.dto.TaskRequest;
import br.edu.unesc.tcc.reactive_task_api.dto.TaskResponse;
import br.edu.unesc.tcc.reactive_task_api.messaging.TaskCommandGateway;
import br.edu.unesc.tcc.reactive_task_api.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/tarefas")
public class TaskController {

    private final TaskCommandGateway gateway;
    private final TaskService queries;

    public TaskController(TaskCommandGateway gateway, TaskService queries) {
        this.gateway = gateway;
        this.queries = queries;
    }

    @PostMapping
    public Mono<ResponseEntity<TaskResponse>> create(
            @Valid @RequestBody TaskRequest request
    ) {
        return gateway.create(request)
                .map(response -> ResponseEntity
                        .created(URI.create("/tarefas/" + response.id()))
                        .body(response));
    }

    @GetMapping("/{id}")
    public Mono<TaskResponse> findById(@PathVariable Long id) {
        return queries.findById(id);
    }

    @GetMapping
    public Mono<List<TaskResponse>> findAll() {
        return queries.findAll();
    }

    @PutMapping("/{id}")
    public Mono<TaskResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody TaskRequest request
    ) {
        return gateway.update(id, request);
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> delete(@PathVariable Long id) {
        return gateway.delete(id)
                .thenReturn(ResponseEntity.noContent().<Void>build());
    }
}