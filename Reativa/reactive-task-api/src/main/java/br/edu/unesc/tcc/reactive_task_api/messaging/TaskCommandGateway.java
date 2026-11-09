package br.edu.unesc.tcc.reactive_task_api.messaging;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import br.edu.unesc.tcc.reactive_task_api.dto.TaskRequest;
import br.edu.unesc.tcc.reactive_task_api.dto.TaskResponse;
import jakarta.annotation.PreDestroy;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Component
public class TaskCommandGateway {

    private final RabbitTemplate rabbit;
    private final Queue commands;
    private final Queue replies;
    private final Scheduler io;
    private final Semaphore slots;
    private final int rpcTimeoutSeconds;
    private final ConcurrentHashMap<String, CompletableFuture<TaskReply>>
            pending = new ConcurrentHashMap<>();

    public TaskCommandGateway(
            RabbitTemplate rabbit,
            @Qualifier("taskCommandsQueue") Queue commands,
            @Qualifier("taskRepliesQueue") Queue replies,
            @Qualifier("rabbitRequestScheduler") Scheduler io,
            @Value("${app.messaging.max-pending-requests:20}") int maxPendingRequests,
            @Value("${app.messaging.rpc-timeout-seconds:10}") int rpcTimeoutSeconds,
            MeterRegistry registry
    ) {
        if (maxPendingRequests < 1) {
            throw new IllegalArgumentException("O limite de RPCs deve ser positivo");
        }
        if (rpcTimeoutSeconds < 1) {
            throw new IllegalArgumentException("RPC timeout must be positive");
        }
        this.rpcTimeoutSeconds = rpcTimeoutSeconds;
        this.rabbit = rabbit;
        this.commands = commands;
        this.replies = replies;
        this.io = io;
        this.slots = new Semaphore(maxPendingRequests);

        Gauge.builder("app.messaging.rpc.pending", slots,
                        value -> maxPendingRequests - value.availablePermits())
                .description("RPCs aguardando resposta ou timeout")
                .register(registry);
    }

    public Mono<TaskResponse> create(TaskRequest request) {
        return taskResult(TaskOperation.CREATE, null, request, 201);
    }

    public Mono<TaskResponse> findById(Long id) {
        return taskResult(TaskOperation.FIND_BY_ID, id, null, 200);
    }

    public Mono<List<TaskResponse>> findAll() {
        return request(TaskOperation.FIND_ALL, null, null, 200)
                .map(reply -> {
                    if (reply.tasks() == null || reply.task() != null) {
                        throw invalidReply();
                    }
                    return reply.tasks();
                });
    }

    public Mono<TaskResponse> update(Long id, TaskRequest request) {
        return taskResult(TaskOperation.UPDATE, id, request, 200);
    }

    public Mono<Void> delete(Long id) {
        return request(TaskOperation.DELETE, id, null, 204)
                .map(reply -> {
                    if (reply.task() != null || reply.tasks() != null) {
                        throw invalidReply();
                    }
                    return reply;
                })
                .then();
    }

    private Mono<TaskResponse> taskResult(
            TaskOperation operation, Long id, TaskRequest body, int expectedStatus
    ) {
        return request(operation, id, body, expectedStatus)
                .map(reply -> {
                    TaskResponse task = reply.task();
                    if (task == null || task.id() == null || reply.tasks() != null
                            || (id != null && !id.equals(task.id()))) {
                        throw invalidReply();
                    }
                    return task;
                });
    }

    private Mono<TaskReply> request(
            TaskOperation operation, Long id, TaskRequest body, int expectedStatus
    ) {
        return Mono.defer(() -> exchange(new TaskCommand(
                        UUID.randomUUID(), operation, id, body
                )))
                .map(reply -> {
                    if (reply.status() != expectedStatus) {
                        HttpStatus status = switch (reply.status()) {
                            case 400 -> HttpStatus.BAD_REQUEST;
                            case 404 -> HttpStatus.NOT_FOUND;
                            case 503 -> HttpStatus.SERVICE_UNAVAILABLE;
                            default -> HttpStatus.INTERNAL_SERVER_ERROR;
                        };
                        throw new ResponseStatusException(
                                status,
                                reply.detail() == null
                                        ? "Falha ao executar a solicitação."
                                        : reply.detail()
                        );
                    }
                    return reply;
                });
    }

    private ResponseStatusException invalidReply() {
        return new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "A resposta recebida é incompatível com a solicitação."
        );
    }

    public Mono<TaskReply> exchange(TaskCommand command) {
        return Mono.fromCallable(() -> send(command))
                .subscribeOn(io)
                .flatMap(future -> Mono.fromFuture(future, true))
                .onErrorMap(failure -> new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Não foi possível confirmar o resultado da operação.",
                        failure
                ))
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "A resposta do comando está ausente."
                )))
                .map(reply -> {
                    if (!command.commandId().equals(reply.commandId())) {
                        throw new ResponseStatusException(
                                HttpStatus.INTERNAL_SERVER_ERROR,
                                "A resposta não corresponde ao comando."
                        );
                    }
                    return reply;
                });
    }

    private CompletableFuture<TaskReply> send(TaskCommand command) {
        if (!slots.tryAcquire()) {
            throw new RejectedExecutionException("Limite de solicitações pendentes");
        }

        String rpcId = UUID.randomUUID().toString();
        CompletableFuture<TaskReply> future = new CompletableFuture<>();
        pending.put(rpcId, future);
        future.whenComplete((reply, failure) -> {
            pending.remove(rpcId, future);
            slots.release();
        });

        try {
            future.orTimeout(rpcTimeoutSeconds, TimeUnit.SECONDS);
            CorrelationData correlation = new CorrelationData(rpcId);
            correlation.getFuture().whenComplete((confirm, failure) -> {
                if (failure != null) {
                    future.completeExceptionally(failure);
                } else if (!confirm.ack() || correlation.getReturned() != null) {
                    future.completeExceptionally(new RejectedExecutionException(
                            "O RabbitMQ rejeitou a publicação ou não encontrou a fila"
                    ));
                }
            });

            if (!future.isDone()) {
                rabbit.convertAndSend(
                        "", commands.getName(), command,
                        message -> {
                            message.getMessageProperties().setReplyTo(replies.getName());
                            message.getMessageProperties().setCorrelationId(rpcId);
                            return message;
                        },
                        correlation
                );
            }
        } catch (RuntimeException failure) {
            future.completeExceptionally(failure);
        }
        return future;
    }

    @RabbitListener(id = "taskReplies", queues = "#{@taskRepliesQueue.name}", ackMode = "AUTO")
    public void receiveReply(
            TaskReply reply,
            @Header(AmqpHeaders.CORRELATION_ID) String rpcId
    ) {
        CompletableFuture<TaskReply> future = pending.remove(rpcId);
        if (future != null) {
            future.complete(reply);
        }
    }

    @PreDestroy
    public void close() {
        pending.values().forEach(future -> future.completeExceptionally(
                new RejectedExecutionException("O gateway está encerrando")
        ));
    }
}