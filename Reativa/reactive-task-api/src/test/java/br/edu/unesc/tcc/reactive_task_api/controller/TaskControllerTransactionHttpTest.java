// Orientative replacement for the existing test; not compiled or executed.
package br.edu.unesc.tcc.reactive_task_api.controller;

import br.edu.unesc.tcc.reactive_task_api.domain.Priority;
import br.edu.unesc.tcc.reactive_task_api.domain.Status;
import br.edu.unesc.tcc.reactive_task_api.dto.TaskRequest;
import br.edu.unesc.tcc.reactive_task_api.dto.TaskResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.ReactiveTransaction;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.messaging.command-queue=reactive.tasks.commands.transaction-test",
                "app.messaging.command-queue-capacity=100",
                "app.messaging.consumer-concurrency=1",
                "app.messaging.max-pending-requests=20",
                "spring.r2dbc.url=r2dbc:h2:mem:///http-transaction-${random.uuid}"
                        + "?options=DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
        }
)
public class TaskControllerTransactionHttpTest {
    private static final Duration VERIFY_TIMEOUT = Duration.ofSeconds(20);

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private DatabaseClient database;

    @Autowired
    private JsonMapper mapper;

    @MockitoSpyBean
    private ReactiveTransactionManager transactionManager;

    private Sinks.Empty<Void> commitGate;
    private Sinks.Empty<Void> commitFinished;
    private CountDownLatch commitStarted;
    private final AtomicReference<ReactiveTransaction> outerTransaction = new AtomicReference<>();
    private final AtomicBoolean headersSeen = new AtomicBoolean();
    private final AtomicBoolean realCommitDone = new AtomicBoolean();
    private final AtomicBoolean prematureSuccess = new AtomicBoolean();
    private final AtomicInteger gatedCommits = new AtomicInteger();
    private WebClient client;

    enum WriteOperation { CREATE, UPDATE, DELETE }

    @BeforeEach
    void setUp() {
        commitGate = Sinks.empty();
        commitFinished = Sinks.empty();
        commitStarted = new CountDownLatch(1);
        outerTransaction.set(null);
        headersSeen.set(false);
        realCommitDone.set(false);
        prematureSuccess.set(false);
        gatedCommits.set(0);
        client = WebClient.builder().baseUrl("http://localhost:" + port).build();
        StepVerifier.create(database.sql("DELETE FROM processed_commands")
                        .fetch().rowsUpdated()
                        .then(database.sql("DELETE FROM tasks").fetch().rowsUpdated())
                        .then())
                .expectComplete().verify(Duration.ofSeconds(10));
        clearInvocations(transactionManager);
    }

    @AfterEach
    void releaseGateIfAnAssertionFails() {
        commitGate.tryEmitEmpty();
        if (outerTransaction.get() != null) {
            StepVerifier.create(commitFinished.asMono())
                    .expectComplete().verify(Duration.ofSeconds(5));
        }
    }

    @ParameterizedTest
    @EnumSource(WriteOperation.class)
    void shouldRespondOnlyAfterTheRealOuterCommit(WriteOperation operation) {
        prepareExistingTask(operation);
        holdOuterCommit(false);

        StepVerifier.create(send(operation))
                .expectSubscription()
                .then(this::awaitCommitStarted)
                .expectNoEvent(Duration.ofMillis(100))
                .then(() -> {
                    assertFalse(headersSeen.get(), "HTTP headers arrived while commit was held");
                    assertEquals(Sinks.EmitResult.OK, commitGate.tryEmitEmpty());
                })
                .assertNext(response -> {
                    assertEquals(expectedStatus(operation), response.getStatusCode().value());
                    if (operation == WriteOperation.DELETE) {
                        assertNull(response.getBody());
                    } else {
                        TaskResponse task = mapper.readValue(response.getBody(), TaskResponse.class);
                        assertNotNull(task.id());
                        assertEquals(requestBody().titulo(), task.titulo());
                        if (operation == WriteOperation.CREATE) {
                            assertEquals("/tarefas/" + task.id(),
                                    response.getHeaders().getLocation().toString());
                        } else {
                            assertEquals(42L, task.id().longValue());
                        }
                    }
                })
                .expectComplete().verify(VERIFY_TIMEOUT);

        assertEquals(1, gatedCommits.get());
        assertTrue(realCommitDone.get());
        assertFalse(prematureSuccess.get(), "A successful HTTP header preceded the real commit");
        verify(transactionManager, never()).rollback(any());
        assertDatabaseState(operation == WriteOperation.DELETE ? 0 : 1, 1);
    }

    @ParameterizedTest
    @EnumSource(WriteOperation.class)
    void shouldReturn503AfterAControlledFailureBeforePhysicalCommit(WriteOperation operation) {
        prepareExistingTask(operation);
        holdOuterCommit(true);

        StepVerifier.create(send(operation))
                .expectSubscription()
                .then(this::awaitCommitStarted)
                .expectNoEvent(Duration.ofMillis(100))
                .then(() -> {
                    assertFalse(headersSeen.get(), "HTTP headers arrived while commit was held");
                    assertEquals(Sinks.EmitResult.OK, commitGate.tryEmitEmpty());
                })
                .assertNext(response -> assertEquals(503, response.getStatusCode().value()))
                .expectComplete().verify(VERIFY_TIMEOUT);

        assertEquals(1, gatedCommits.get());
        assertFalse(realCommitDone.get());
        assertFalse(prematureSuccess.get());
        verify(transactionManager).rollback(outerTransaction.get());
        assertDatabaseState(operation == WriteOperation.CREATE ? 0 : 1, 0);
        assertOriginalPreserved(operation);
    }

    @ParameterizedTest
    @EnumSource(WriteOperation.class)
    void shouldReturn503WhenTheRealTransactionCannotStart(WriteOperation operation) {
        prepareExistingTask(operation);
        doReturn(Mono.error(new CannotCreateTransactionException(
                "Falha controlada ao iniciar a transação")))
                .when(transactionManager).getReactiveTransaction(any());

        StepVerifier.create(send(operation))
                .assertNext(response -> assertEquals(503, response.getStatusCode().value()))
                .expectComplete().verify(VERIFY_TIMEOUT);

        verify(transactionManager).getReactiveTransaction(any());
        verify(transactionManager, never()).commit(any());
        assertFalse(realCommitDone.get());
        assertFalse(prematureSuccess.get());
        assertDatabaseState(operation == WriteOperation.CREATE ? 0 : 1, 0);
        assertOriginalPreserved(operation);
    }

    @SuppressWarnings("unchecked")
    private void holdOuterCommit(boolean failBeforeCommit) {
        doAnswer(invocation -> {
            ReactiveTransaction transaction = invocation.getArgument(0);
            Mono<Void> realCommit = (Mono<Void>) invocation.callRealMethod();
            if (!transaction.isNewTransaction()) {
                return realCommit;
            }
            return Mono.defer(() -> {
                outerTransaction.set(transaction);
                gatedCommits.incrementAndGet();
                commitStarted.countDown();
                return commitGate.asMono()
                        .then(Mono.defer(() -> failBeforeCommit
                                ? transactionManager.rollback(transaction)
                                .then(Mono.<Void>error(new DataAccessResourceFailureException(
                                        "Falha controlada antes do commit físico")))
                                : realCommit.doOnSuccess(ignored -> realCommitDone.set(true))))
                        .doFinally(signal -> commitFinished.tryEmitEmpty());
            });
        }).when(transactionManager).commit(any());
    }

    private Mono<ResponseEntity<String>> send(WriteOperation operation) {
        WebClient.RequestHeadersSpec<?> request = switch (operation) {
            case CREATE -> client.post().uri("/tarefas")
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(requestBody());
            case UPDATE -> client.put().uri("/tarefas/42")
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(requestBody());
            case DELETE -> client.delete().uri("/tarefas/42");
        };
        return request.exchangeToMono(response -> {
            headersSeen.set(true);
            if (response.statusCode().is2xxSuccessful() && !realCommitDone.get()) {
                prematureSuccess.set(true);
            }
            return response.toEntity(String.class);
        });
    }

    private void prepareExistingTask(WriteOperation operation) {
        if (operation != WriteOperation.CREATE) {
            StepVerifier.create(database.sql("INSERT INTO tasks "
                                    + "(id, titulo, descricao, prioridade, status, data_limite) "
                                    + "VALUES (42, :title, :description, :priority, :status, :dueDate)")
                            .bind("title", "Título anterior")
                            .bind("description", "Descrição anterior")
                            .bind("priority", Priority.MEDIA.name())
                            .bind("status", Status.ABERTA.name())
                            .bind("dueDate", LocalDate.of(2020, 1, 15))
                            .fetch().rowsUpdated())
                    .expectNext(1L).expectComplete().verify(Duration.ofSeconds(10));
        }
        clearInvocations(transactionManager);
    }

    private void assertDatabaseState(long expectedTasks, long expectedCommands) {
        Mono<Long> taskCount = database.sql("SELECT COUNT(*) AS total FROM tasks")
                .map((row, metadata) -> row.get("total", Long.class)).one();
        Mono<Long> commandCount = database.sql("SELECT COUNT(*) AS total FROM processed_commands")
                .map((row, metadata) -> row.get("total", Long.class)).one();
        StepVerifier.create(Mono.zip(taskCount, commandCount))
                .assertNext(counts -> {
                    assertEquals(expectedTasks, counts.getT1().longValue());
                    assertEquals(expectedCommands, counts.getT2().longValue());
                })
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    private void assertOriginalPreserved(WriteOperation operation) {
        if (operation == WriteOperation.CREATE) {
            return;
        }
        StepVerifier.create(database.sql("SELECT titulo, descricao, prioridade, status, data_limite "
                                + "FROM tasks WHERE id = 42")
                        .map((row, metadata) -> new TaskRequest(
                                row.get("titulo", String.class), row.get("descricao", String.class),
                                Priority.valueOf(row.get("prioridade", String.class)),
                                Status.valueOf(row.get("status", String.class)),
                                row.get("data_limite", LocalDate.class)))
                        .one())
                .expectNext(new TaskRequest("Título anterior", "Descrição anterior",
                        Priority.MEDIA, Status.ABERTA, LocalDate.of(2020, 1, 15)))
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    private void awaitCommitStarted() {
        try {
            assertTrue(commitStarted.await(5, TimeUnit.SECONDS),
                    "O commit externo não foi alcançado dentro do limite");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Teste interrompido", failure);
        }
    }

    private static int expectedStatus(WriteOperation operation) {
        return switch (operation) {
            case CREATE -> 201;
            case UPDATE -> 200;
            case DELETE -> 204;
        };
    }

    private static TaskRequest requestBody() {
        return new TaskRequest("Título atualizado", null, Priority.ALTA,
                Status.CONCLUIDA, LocalDate.of(2020, 3, 10));
    }
}
