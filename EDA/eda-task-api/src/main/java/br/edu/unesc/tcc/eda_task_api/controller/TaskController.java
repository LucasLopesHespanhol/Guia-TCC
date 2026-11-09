package br.edu.unesc.tcc.eda_task_api.controller;

import br.edu.unesc.tcc.eda_task_api.dto.SubmissionResponse;
import br.edu.unesc.tcc.eda_task_api.dto.TaskRequest;
import br.edu.unesc.tcc.eda_task_api.dto.TaskResponse;
import br.edu.unesc.tcc.eda_task_api.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping("/tarefas")
    public List<TaskResponse> findAll() {
        return taskService.findAll();
    }

    @GetMapping("/tarefas/{id}")
    public ResponseEntity<TaskResponse> findById(@PathVariable("id") Long id) {
        return ResponseEntity.of(taskService.findById(id));
    }

    @PostMapping("/tarefas")
    public ResponseEntity<SubmissionResponse> create(
            @Valid @RequestBody TaskRequest request) {
        return accepted(taskService.registerCreate(request));
    }

    @PutMapping("/tarefas/{id}")
    public ResponseEntity<SubmissionResponse> update(
            @PathVariable("id") Long id,
            @Valid @RequestBody TaskRequest request) {
        return accepted(taskService.registerUpdate(id, request));
    }

    @DeleteMapping("/tarefas/{id}")
    public ResponseEntity<SubmissionResponse> delete(@PathVariable("id") Long id) {
        return accepted(taskService.registerDelete(id));
    }

    @GetMapping("/solicitacoes/{id}")
    public ResponseEntity<SubmissionResponse> findSubmission(
            @PathVariable("id") UUID id) {
        return ResponseEntity.of(taskService.findSubmission(id));
    }

    private ResponseEntity<SubmissionResponse> accepted(SubmissionResponse response) {
        return ResponseEntity.accepted()
                .location(URI.create("/solicitacoes/" + response.id()))
                .body(response);
    }
}
