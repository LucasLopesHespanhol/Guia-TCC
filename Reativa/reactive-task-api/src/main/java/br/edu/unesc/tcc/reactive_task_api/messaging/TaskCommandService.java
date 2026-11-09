package br.edu.unesc.tcc.reactive_task_api.messaging;

import br.edu.unesc.tcc.reactive_task_api.domain.ProcessedCommand;
import br.edu.unesc.tcc.reactive_task_api.repository.ProcessedCommandRepository;
import br.edu.unesc.tcc.reactive_task_api.service.TaskService;
import jakarta.validation.Validator;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class TaskCommandService {
    private final TaskService tasks;
    private final ProcessedCommandRepository commands;
    private final JsonMapper mapper;
    private final Validator validator;
    private final TransactionalOperator transactions;

    public TaskCommandService(
            TaskService tasks,
            ProcessedCommandRepository commands,
            JsonMapper mapper,
            Validator validator,
            ReactiveTransactionManager transactionManager) {
        this.tasks = tasks;
        this.commands = commands;
        this.mapper = mapper;
        this.validator = validator;
        this.transactions = TransactionalOperator.create(transactionManager);
    }

    public Mono<TaskReply> execute(TaskCommand command) {
        return Mono.defer(() -> {
            if (command == null || command.commandId() == null
                    || command.operation() == null) {
                return Mono.just(error(command == null ? null : command.commandId(),
                        400, "O comando está incompleto."));
            }

            boolean needsId = switch (command.operation()) {
                case FIND_BY_ID, UPDATE, DELETE -> true;
                default -> false;
            };
            boolean needsRequest = switch (command.operation()) {
                case CREATE, UPDATE -> true;
                default -> false;
            };
            if ((command.taskId() != null) != needsId
                    || (command.request() != null) != needsRequest) {
                return Mono.just(error(command.commandId(), 400,
                        "Os campos não correspondem à operação."));
            }
            if (needsRequest && !validator.validate(command.request()).isEmpty()) {
                return Mono.just(error(command.commandId(), 400,
                        "Os dados da tarefa não atendem ao contrato."));
            }

            if (command.operation() == TaskOperation.FIND_BY_ID
                    || command.operation() == TaskOperation.FIND_ALL) {
                return perform(command);
            }

            String requestHash = sha256(command.operation().name() + "\n"
                    + command.taskId() + "\n"
                    + mapper.writeValueAsString(command.request()));
            UUID commandId = command.commandId();
            Mono<TaskReply> operation = commands.findById(commandId)
                    .flatMap(found -> restore(found, command, requestHash))
                    .switchIfEmpty(Mono.defer(() -> commands
                            .reserve(commandId, requestHash)
                            .then(perform(command))
                            .flatMap(reply -> commands.complete(
                                            commandId, mapper.writeValueAsString(reply))
                                    .thenReturn(reply))));

            return transactions.transactional(operation.flux())
                    .single()
                    .onErrorResume(DuplicateKeyException.class, failure ->
                            commands.findById(commandId)
                                    .switchIfEmpty(Mono.error(failure))
                                    .flatMap(found -> restore(found, command, requestHash)));
        });
    }

    private Mono<TaskReply> perform(TaskCommand command) {
        return switch (command.operation()) {
            case CREATE -> tasks.create(command.request())
                    .map(task -> new TaskReply(command.commandId(), 201,
                            task, null, null));
            case FIND_BY_ID -> tasks.findById(command.taskId())
                    .map(task -> new TaskReply(command.commandId(), 200,
                            task, null, null));
            case FIND_ALL -> tasks.findAll()
                    .map(list -> new TaskReply(command.commandId(), 200,
                            null, list, null));
            case UPDATE -> tasks.update(command.taskId(), command.request())
                    .map(task -> new TaskReply(command.commandId(), 200,
                            task, null, null));
            case DELETE -> tasks.delete(command.taskId())
                    .thenReturn(new TaskReply(command.commandId(), 204,
                            null, null, null));
        };
    }

    private Mono<TaskReply> restore(
            ProcessedCommand processed, TaskCommand command, String requestHash) {
        return Mono.fromSupplier(() -> {
            if (!processed.requestHash().equals(requestHash)) {
                return error(processed.commandId(), 400,
                        "O identificador do comando foi reutilizado com outro conteúdo.");
            }
            if (processed.responseJson() == null) {
                throw new IllegalStateException("O comando não contém resultado confirmado.");
            }
            TaskReply reply = mapper.readValue(processed.responseJson(), TaskReply.class);
            if (reply == null || !processed.commandId().equals(reply.commandId())) {
                throw new IllegalStateException("O resultado armazenado é incompatível.");
            }
            boolean valid = switch (command.operation()) {
                case CREATE -> reply.status() == 201 && reply.task() != null
                        && reply.task().id() != null && reply.tasks() == null;
                case UPDATE -> reply.status() == 200 && reply.task() != null
                        && command.taskId().equals(reply.task().id()) && reply.tasks() == null;
                case DELETE -> reply.status() == 204
                        && reply.task() == null && reply.tasks() == null;
                default -> false;
            };
            if (!valid) {
                throw new IllegalStateException("O resultado não corresponde à mutação.");
            }
            return reply;
        });
    }

    private TaskReply error(UUID commandId, int status, String detail) {
        return new TaskReply(commandId, status, null, null, detail);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponível", exception);
        }
    }
}