package br.edu.unesc.tcc.reactive_task_api.messaging;

import br.edu.unesc.tcc.reactive_task_api.exception.TaskNotFoundException;
import io.r2dbc.spi.R2dbcNonTransientResourceException;
import io.r2dbc.spi.R2dbcTimeoutException;
import io.r2dbc.spi.R2dbcTransientResourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

@Component
public class TaskCommandListener {

    private static final Logger log = LoggerFactory.getLogger(TaskCommandListener.class);
    private final TaskCommandService service;
    private final Scheduler io;

    public TaskCommandListener(
            TaskCommandService service,
            @Qualifier("rabbitReplyScheduler") Scheduler io
    ) {
        this.service = service;
        this.io = io;
    }

    @RabbitListener(
            id = "taskCommands",
            queues = "#{@taskCommandsQueue.name}",
            ackMode = "MANUAL",
            concurrency = "${app.messaging.consumer-concurrency:1-4}"
    )
    public Mono<TaskReply> receive(TaskCommand command) {
        return service.execute(command)
                .onErrorResume(TaskNotFoundException.class, failure ->
                        Mono.just(new TaskReply(
                                command.commandId(), 404, null, null,
                                failure.getMessage()
                        ))
                )
                .onErrorResume(failure -> {
                    log.error("Falha no comando {}", command.commandId(), failure);
                    boolean unavailable = failure instanceof DataAccessResourceFailureException
                            || failure instanceof TransientDataAccessException
                            || failure instanceof CannotCreateTransactionException
                            || failure instanceof R2dbcTransientResourceException
                            || failure instanceof R2dbcNonTransientResourceException
                            || failure instanceof R2dbcTimeoutException;
                    return Mono.just(new TaskReply(
                            command.commandId(), unavailable ? 503 : 500, null, null,
                            unavailable
                                    ? "O recurso necessário está indisponível."
                                    : "Ocorreu uma falha ao processar o comando."
                    ));
                })
                .publishOn(io);
    }
}