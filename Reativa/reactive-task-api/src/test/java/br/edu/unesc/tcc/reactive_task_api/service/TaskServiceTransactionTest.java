package br.edu.unesc.tcc.reactive_task_api.service;

import br.edu.unesc.tcc.reactive_task_api.domain.Priority;
import br.edu.unesc.tcc.reactive_task_api.domain.Status;
import br.edu.unesc.tcc.reactive_task_api.domain.Task;
import br.edu.unesc.tcc.reactive_task_api.dto.TaskRequest;
import br.edu.unesc.tcc.reactive_task_api.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.transaction.ReactiveTransaction;
import org.springframework.transaction.ReactiveTransactionManager;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class TaskServiceTransactionTest {

    private static final Duration VERIFY_TIMEOUT = Duration.ofSeconds(5);

    private TaskRepository repository;
    private ReactiveTransactionManager transactionManager;
    private ReactiveTransaction transaction;
    private Sinks.Empty<Void> commitGate;
    private TaskService service;

    enum WriteOperation {
        CREATE,
        UPDATE,
        DELETE
    }

    @BeforeEach
    void setUp() {
        repository = mock(TaskRepository.class);
        transactionManager = mock(ReactiveTransactionManager.class);
        transaction = mock(ReactiveTransaction.class);
        commitGate = Sinks.empty();

        when(transactionManager.getReactiveTransaction(any()))
                .thenReturn(Mono.just(transaction));

        when(transactionManager.commit(transaction))
                .thenReturn(commitGate.asMono());

        when(transactionManager.rollback(transaction))
                .thenReturn(Mono.empty());

        when(repository.findById(42L))
                .thenAnswer(invocation -> Mono.just(existingTask()));

        when(repository.save(any(Task.class)))
                .thenAnswer(invocation -> {
                    Task task = invocation.getArgument(0);
                    task.setId(42L);
                    return Mono.just(task);
                });

        when(repository.deleteById(42L))
                .thenReturn(Mono.empty());

        service = new TaskService(repository, transactionManager);
    }

    @ParameterizedTest
    @EnumSource(WriteOperation.class)
    void shouldWaitForCommitBeforeSuccess(WriteOperation operation) {
        StepVerifier.withVirtualTime(() -> execute(operation))
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(1))
                .then(() -> {
                    verify(transactionManager).commit(transaction);

                    assertEquals(
                            Sinks.EmitResult.OK,
                            commitGate.tryEmitEmpty()
                    );
                })
                .expectNext(true)
                .expectComplete()
                .verify(VERIFY_TIMEOUT);

        verify(transactionManager, never()).rollback(transaction);
    }

    @ParameterizedTest
    @EnumSource(WriteOperation.class)
    void shouldPropagateCommitFailure(WriteOperation operation) {
        RuntimeException failure =
                new IllegalStateException("Falha simulada no commit");

        StepVerifier.withVirtualTime(() -> execute(operation))
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(1))
                .then(() -> {
                    verify(transactionManager).commit(transaction);

                    assertEquals(
                            Sinks.EmitResult.OK,
                            commitGate.tryEmitError(failure)
                    );
                })
                .expectErrorMatches(error -> hasCause(error, failure))
                .verify(VERIFY_TIMEOUT);
    }

    @ParameterizedTest
    @EnumSource(WriteOperation.class)
    void shouldRollbackWhenOperationFails(WriteOperation operation) {
        RuntimeException failure =
                new IllegalStateException("Falha simulada na operação");

        when(repository.save(any(Task.class)))
                .thenReturn(Mono.error(failure));

        when(repository.deleteById(42L))
                .thenReturn(Mono.error(failure));

        StepVerifier.create(execute(operation))
                .expectErrorMatches(error -> hasCause(error, failure))
                .verify(VERIFY_TIMEOUT);

        verify(transactionManager).rollback(transaction);
        verify(transactionManager, never()).commit(transaction);
    }

    private Mono<Boolean> execute(WriteOperation operation) {
        return switch (operation) {
            case CREATE -> service.create(request()).map(response -> true);
            case UPDATE -> service.update(42L, request()).map(response -> true);
            case DELETE -> service.delete(42L).thenReturn(true);
        };
    }

    private static TaskRequest request() {
        return new TaskRequest(
                "Título atualizado",
                null,
                Priority.ALTA,
                Status.CONCLUIDA,
                LocalDate.of(2020, 3, 10)
        );
    }

    private static Task existingTask() {
        Task task = new Task();
        task.setId(42L);
        task.setTitulo("Título anterior");
        task.setDescricao("Descrição anterior");
        task.setPrioridade(Priority.MEDIA);
        task.setStatus(Status.ABERTA);
        task.setDataLimite(LocalDate.of(2020, 1, 15));
        return task;
    }

    private static boolean hasCause(
            Throwable error,
            Throwable expected
    ) {
        for (Throwable current = error;
             current != null;
             current = current.getCause()) {

            if (current == expected) {
                return true;
            }
        }

        return false;
    }
}