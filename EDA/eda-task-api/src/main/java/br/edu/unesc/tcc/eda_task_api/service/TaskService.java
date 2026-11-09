package br.edu.unesc.tcc.eda_task_api.service;

import br.edu.unesc.tcc.eda_task_api.domain.SubmissionStatus;
import br.edu.unesc.tcc.eda_task_api.domain.Task;
import br.edu.unesc.tcc.eda_task_api.domain.TaskSubmission;
import br.edu.unesc.tcc.eda_task_api.dto.SubmissionResponse;
import br.edu.unesc.tcc.eda_task_api.dto.TaskRequest;
import br.edu.unesc.tcc.eda_task_api.dto.TaskResponse;
import br.edu.unesc.tcc.eda_task_api.events.TaskEvent;
import br.edu.unesc.tcc.eda_task_api.events.TaskEventType;
import br.edu.unesc.tcc.eda_task_api.mapper.TaskMapper;
import br.edu.unesc.tcc.eda_task_api.repository.SubmissionRepository;
import br.edu.unesc.tcc.eda_task_api.repository.TaskRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);
    private final TaskRepository taskRepository;
    private final SubmissionRepository submissionRepository;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher events;
    private final String runId;
    private final MeterRegistry meters;
    private final AtomicLong registered = new AtomicLong();

    public TaskService(TaskRepository taskRepository, SubmissionRepository submissionRepository,
                       JsonMapper jsonMapper, ApplicationEventPublisher events,
                       @Value("${eda.run-id:desenvolvimento}") String runId,
                       MeterRegistry meters) {
        if (runId.isBlank() || runId.length() > 100) {
            throw new IllegalArgumentException("eda.run-id deve ter entre 1 e 100 caracteres");
        }
        this.taskRepository = taskRepository;
        this.submissionRepository = submissionRepository;
        this.jsonMapper = jsonMapper;
        this.events = events;
        this.runId = runId;
        this.meters = meters;
        Gauge.builder("eda.submissions.registered", registered, AtomicLong::get)
                .description("Solicitacoes registradas ainda sem estado terminal")
                .register(meters);
    }

    public List<TaskResponse> findAll() {
        return taskRepository.findAllByOrderByIdAsc().stream().map(TaskMapper::toResponse).toList();
    }

    public Optional<TaskResponse> findById(Long id) {
        return taskRepository.findById(id).map(TaskMapper::toResponse);
    }

    public Optional<SubmissionResponse> findSubmission(UUID id) {
        return submissionRepository.findStatusView(id).map(view -> new SubmissionResponse(
                view.id(), view.tipo(), view.estado(), view.tarefaId(),
                view.resultadoJson() == null ? null
                        : jsonMapper.readValue(view.resultadoJson(), TaskResponse.class),
                view.erro(), view.registradaEm(), view.finalizadaEm()));
    }

    @Transactional
    public SubmissionResponse registerCreate(TaskRequest request) {
        return register(TaskEventType.CREATION_REQUEST_REGISTERED, null, request);
    }

    @Transactional
    public SubmissionResponse registerUpdate(Long id, TaskRequest request) {
        return register(TaskEventType.UPDATE_REQUEST_REGISTERED, id, request);
    }

    @Transactional
    public SubmissionResponse registerDelete(Long id) {
        return register(TaskEventType.DELETION_REQUEST_REGISTERED, id, null);
    }

    private SubmissionResponse register(TaskEventType type, Long taskId, TaskRequest request) {
        String data = request == null ? null : jsonMapper.writeValueAsString(request);
        TaskSubmission submission = submissionRepository.save(new TaskSubmission(type, taskId, data));
        afterCommit(registered::incrementAndGet);
        events.publishEvent(new TaskEvent(submission.getId(), submission.getId(), type,
                submission.getRegistradaEm(), taskId, 1, runId));
        return toResponse(submission);
    }

    @Transactional
    public void process(TaskEvent event) {
        TaskSubmission submission = registeredSubmission(event);
        if (submission.getEstado() != SubmissionStatus.REGISTRADA) {
            log.info("Solicitação terminal preservada: id={}, estado={}",
                    submission.getId(), submission.getEstado());
            return;
        }

        Task task;
        TaskEventType resultType;
        switch (submission.getTipo()) {
            case CREATION_REQUEST_REGISTERED -> {
                TaskRequest request = jsonMapper.readValue(submission.getDadosJson(), TaskRequest.class);
                task = taskRepository.save(TaskMapper.toTask(request));
                resultType = TaskEventType.TASK_CREATED;
            }
            case UPDATE_REQUEST_REGISTERED, DELETION_REQUEST_REGISTERED -> {
                task = taskRepository.findById(submission.getTarefaId()).orElse(null);
                if (task == null) {
                    submission.reject("NOT_FOUND: tarefa não encontrada");
                    recordFinished(submission);
                    return;
                }
                if (submission.getTipo() == TaskEventType.UPDATE_REQUEST_REGISTERED) {
                    TaskRequest request = jsonMapper.readValue(submission.getDadosJson(), TaskRequest.class);
                    TaskMapper.updateTask(task, request);
                    resultType = TaskEventType.TASK_UPDATED;
                } else {
                    taskRepository.delete(task);
                    resultType = TaskEventType.TASK_DELETED;
                }
            }
            default -> throw new IllegalArgumentException("Tipo não representa registro de solicitação");
        }
        submission.complete(task.getId(), jsonMapper.writeValueAsString(TaskMapper.toResponse(task)));
        recordFinished(submission);
        events.publishEvent(new TaskEvent(UUID.randomUUID(), submission.getId(), resultType,
                Instant.now(), task.getId(), 1, event.runId()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failRegistration(TaskEvent event, String reason) {
        TaskSubmission submission = registeredSubmission(event);
        if (submission.getEstado() == SubmissionStatus.REGISTRADA) {
            submission.fail(reason);
            recordFinished(submission);
        }
    }

    private void recordFinished(TaskSubmission submission) {
        String tipo = submission.getTipo().name();
        String estado = submission.getEstado().name();
        Duration elapsed = Duration.between(submission.getRegistradaEm(), submission.getFinalizadaEm());
        afterCommit(() -> {
            registered.decrementAndGet();
            Timer.builder("eda.submission.completion")
                    .description("Tempo do registro da solicitacao ate o estado terminal")
                    .tag("tipo", tipo)
                    .tag("estado", estado)
                    .publishPercentileHistogram()
                    .minimumExpectedValue(Duration.ofMillis(1))
                    .maximumExpectedValue(Duration.ofSeconds(60))
                    .register(meters)
                    .record(elapsed);
        });
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private TaskSubmission registeredSubmission(TaskEvent event) {
        event.validate();
        if (!event.type().isRegistration() || !event.eventId().equals(event.submissionId())) {
            throw new IllegalArgumentException("Identidade inválida para evento de registro");
        }
        TaskSubmission submission = submissionRepository.findForProcessing(event.submissionId())
                .orElseThrow(() -> new IllegalArgumentException("Solicitação não existe no banco local"));
        Long originalTarget = submission.getTipo() == TaskEventType.CREATION_REQUEST_REGISTERED
                ? null : submission.getTarefaId();
        if (submission.getTipo() != event.type()
                || !submission.getRegistradaEm().equals(event.occurredAt())
                || !Objects.equals(originalTarget, event.taskId())) {
            throw new IllegalArgumentException("Evento não corresponde à solicitação registrada");
        }
        return submission;
    }

    private SubmissionResponse toResponse(TaskSubmission submission) {
        TaskResponse result = submission.getResultadoJson() == null ? null
                : jsonMapper.readValue(submission.getResultadoJson(), TaskResponse.class);
        return new SubmissionResponse(submission.getId(), submission.getTipo(), submission.getEstado(),
                submission.getTarefaId(), result, submission.getErro(),
                submission.getRegistradaEm(), submission.getFinalizadaEm());
    }
}