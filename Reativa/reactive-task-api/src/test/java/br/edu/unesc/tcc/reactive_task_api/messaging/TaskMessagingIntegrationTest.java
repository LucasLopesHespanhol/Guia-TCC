package br.edu.unesc.tcc.reactive_task_api.messaging;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import java.util.List;
import static org.mockito.Mockito.doAnswer;
import br.edu.unesc.tcc.reactive_task_api.domain.Priority;
import br.edu.unesc.tcc.reactive_task_api.domain.Status;
import br.edu.unesc.tcc.reactive_task_api.dto.TaskRequest;
import br.edu.unesc.tcc.reactive_task_api.dto.TaskResponse;
import br.edu.unesc.tcc.reactive_task_api.mapper.TaskMapper;
import br.edu.unesc.tcc.reactive_task_api.repository.ProcessedCommandRepository;
import br.edu.unesc.tcc.reactive_task_api.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.messaging.max-pending-requests=2",
                "app.messaging.consumer-concurrency=1",
                "app.messaging.command-queue=reactive.tasks.commands.test",
                "app.messaging.command-queue-capacity=100",
                "spring.r2dbc.url=r2dbc:h2:mem:///messaging-${random.uuid}"
                        + "?options=DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
        }
)
class TaskMessagingIntegrationTest {

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private TaskCommandGateway gateway;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private DatabaseClient database;

    @MockitoSpyBean
    private ProcessedCommandRepository commands;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(15))
                .build();
        StepVerifier.create(database.sql("DELETE FROM processed_commands")
                        .fetch().rowsUpdated().then(tasks.deleteAll()))
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void shouldCreateThroughHttpAndRabbit() {
        TaskResponse created = createThroughHttp(request("Criar por mensagem"));
        assertNotNull(created.id());
        assertEquals("Criar por mensagem", created.titulo());
        assertCounts(1, 1);
    }

    @Test
    void shouldReuseResultWhenTheSameCommandIsDeliveredAgain() {
        TaskCommand command = new TaskCommand(
                UUID.randomUUID(), TaskOperation.CREATE, null, request("Reentrega")
        );
        TaskReply first = exchange(command);
        TaskReply second = exchange(command);
        assertEquals(201, first.status());
        assertEquals(first, second);
        assertCounts(1, 1);
    }

    @Test
    void shouldRejectChangedContentOperationOrTargetWithTheSameCommandId() {
        UUID commandId = UUID.randomUUID();
        TaskReply first = exchange(new TaskCommand(
                commandId, TaskOperation.CREATE, null, request("Original")
        ));
        assertEquals(201, first.status());

        TaskReply conflictingPayload = exchange(new TaskCommand(
                commandId, TaskOperation.CREATE, null, request("Outro conteúdo")
        ));
        assertEquals(400, conflictingPayload.status());

        TaskReply conflictingOperation = exchange(new TaskCommand(
                commandId, TaskOperation.UPDATE, first.task().id(), request("Original")
        ));
        assertEquals(400, conflictingOperation.status());
        assertEquals(first.task(), findThroughHttp(first.task().id()));

        UUID updateId = UUID.randomUUID();
        TaskReply update = exchange(new TaskCommand(
                updateId, TaskOperation.UPDATE, first.task().id(), request("Original")
        ));
        assertEquals(200, update.status());
        TaskReply conflictingTarget = exchange(new TaskCommand(
                updateId, TaskOperation.UPDATE, first.task().id() + 1, request("Original")
        ));
        assertEquals(400, conflictingTarget.status());
        assertCounts(1, 2);
    }

    @ParameterizedTest
    @EnumSource(value = TaskOperation.class, names = {"CREATE", "UPDATE", "DELETE"})
    void shouldRollbackWriteWhenStoringItsReplyFails(TaskOperation operation) {
        TaskResponse original = operation == TaskOperation.CREATE
                ? null : seedTask(request("Original preservada"));

        doReturn(Mono.error(new DataAccessResourceFailureException(
                "Falha controlada ao armazenar resultado"
        ))).when(commands).complete(any(UUID.class), anyString());

        WebTestClient.ResponseSpec response = switch (operation) {
            case CREATE -> client.post().uri("/tarefas")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request("Deve reverter"))
                    .exchange();
            case UPDATE -> client.put().uri("/tarefas/{id}", original.id())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request("Alteração que deve reverter"))
                    .exchange();
            case DELETE -> client.delete().uri("/tarefas/{id}", original.id())
                    .exchange();
            default -> throw new IllegalArgumentException("Operação não é escrita");
        };

        response.expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.status").isEqualTo(503);
        verify(commands).complete(any(UUID.class), anyString());
        assertCounts(original == null ? 0 : 1, 0);
        if (original != null) {
            assertEquals(original, findThroughHttp(original.id()));
        }
    }

    @Test
    void shouldPreserveTheHttpCrudContractThroughRabbit() {
        client.get().uri("/tarefas").exchange()
                .expectStatus().isOk()
                .expectBodyList(TaskResponse.class).hasSize(0);

        TaskResponse first = createThroughHttp(request("Primeira"));
        TaskResponse second = createThroughHttp(request("Segunda"));
        assertTrue(first.id() < second.id());
        assertEquals(first, findThroughHttp(first.id()));

        client.get().uri("/tarefas").exchange()
                .expectStatus().isOk()
                .expectBodyList(TaskResponse.class)
                .hasSize(2)
                .value(list -> {
                    assertEquals(first, list.get(0));
                    assertEquals(second, list.get(1));
                });

        TaskRequest replacement = new TaskRequest(
                "Substituída", null, Priority.ALTA,
                Status.CONCLUIDA, LocalDate.of(2020, 1, 1)
        );
        TaskResponse expected = new TaskResponse(
                first.id(), replacement.titulo(), replacement.descricao(),
                replacement.prioridade(), replacement.status(), replacement.dataLimite()
        );
        client.put().uri("/tarefas/{id}", first.id())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(replacement)
                .exchange()
                .expectStatus().isOk()
                .expectBody(TaskResponse.class)
                .isEqualTo(expected);
        assertEquals(expected, findThroughHttp(first.id()));

        client.delete().uri("/tarefas/{id}", first.id()).exchange()
                .expectStatus().isNoContent()
                .expectBody().isEmpty();
        client.get().uri("/tarefas/{id}", first.id()).exchange()
                .expectStatus().isNotFound();
        client.put().uri("/tarefas/{id}", first.id())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(replacement)
                .exchange()
                .expectStatus().isNotFound();
        client.delete().uri("/tarefas/{id}", first.id()).exchange()
                .expectStatus().isNotFound();
        client.get().uri("/tarefas").exchange()
                .expectStatus().isOk()
                .expectBodyList(TaskResponse.class)
                .hasSize(1)
                .value(list -> assertEquals(second, list.get(0)));

        // Duas criações, uma atualização e uma exclusão concluídas.
        assertCounts(1, 4);
    }

    @Test
    void shouldReuseUpdateSnapshotWithoutApplyingItAgain() {
        TaskResponse created = createThroughHttp(request("Original"));
        TaskCommand update = new TaskCommand(
                UUID.randomUUID(), TaskOperation.UPDATE,
                created.id(), request("Primeira atualização")
        );
        TaskReply firstReply = exchange(update);
        assertEquals(200, firstReply.status());
        assertEquals("Primeira atualização", firstReply.task().titulo());

        TaskRequest later = request("Atualização posterior");
        client.put().uri("/tarefas/{id}", created.id())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(later)
                .exchange()
                .expectStatus().isOk();

        TaskReply repeated = exchange(update);
        assertEquals(firstReply, repeated);
        assertEquals("Atualização posterior", findThroughHttp(created.id()).titulo());
        assertCounts(1, 3);
    }

    @Test
    void shouldReuseDeleteResultAfterTheTaskIsGone() {
        TaskResponse created = createThroughHttp(request("Excluir uma vez"));
        TaskCommand deletion = new TaskCommand(
                UUID.randomUUID(), TaskOperation.DELETE, created.id(), null
        );
        TaskReply first = exchange(deletion);
        TaskReply repeated = exchange(deletion);
        assertEquals(204, first.status());
        assertEquals(first, repeated);
        assertNull(repeated.task());
        client.get().uri("/tarefas/{id}", created.id()).exchange()
                .expectStatus().isNotFound();
        assertCounts(0, 2);
    }

    @Test
    void shouldRejectExcessPendingRequestAndRecover() {
        Sinks.Empty<Void> entered = Sinks.empty();
        Sinks.Empty<Void> release = Sinks.empty();
        Gauge pending = meters.get("app.messaging.rpc.pending").gauge();

        doAnswer(invocation -> {
            Mono<?> original = (Mono<?>) invocation.callRealMethod();
            return original.then(Mono.defer(() -> {
                entered.tryEmitEmpty();
                return release.asMono();
            }));
        }).when(commands).complete(any(UUID.class), anyString());

        WebClient asyncClient = WebClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();

        Mono<Integer> excess = entered.asMono()
                .then(Flux.interval(Duration.ZERO, Duration.ofMillis(25))
                        .filter(tick -> pending.value() == 2.0)
                        .next()
                        .then())
                .then(postStatus(asyncClient, "Deve ser rejeitada"))
                .timeout(Duration.ofSeconds(5))
                .doFinally(signal -> release.tryEmitEmpty());

        StepVerifier.create(Flux.mergeDelayError(
                        1,
                        postStatus(asyncClient, "Solicitação pendente 1"),
                        postStatus(asyncClient, "Solicitação pendente 2"),
                        excess
                ).collectList())
                .assertNext(statuses -> assertEquals(
                        List.of(201, 201, 503),
                        statuses.stream().sorted().toList()
                ))
                .expectComplete()
                .verify(Duration.ofSeconds(20));

        StepVerifier.create(Flux.interval(Duration.ZERO, Duration.ofMillis(25))
                        .filter(tick -> pending.value() == 0.0)
                        .next()
                        .then())
                .expectComplete()
                .verify(Duration.ofSeconds(2));

        assertCounts(2, 2);
        createThroughHttp(request("Solicitação após recuperação"));
        assertCounts(3, 3);
    }

    private Mono<Integer> postStatus(WebClient asyncClient, String title) {
        return asyncClient.post().uri("/tarefas")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request(title))
                .exchangeToMono(response -> response.releaseBody()
                        .thenReturn(response.statusCode().value()));
    }

    private TaskReply exchange(TaskCommand command) {
        AtomicReference<TaskReply> result = new AtomicReference<>();
        StepVerifier.create(gateway.exchange(command))
                .assertNext(result::set)
                .expectComplete().verify(Duration.ofSeconds(20));
        return result.get();
    }

    private TaskResponse createThroughHttp(TaskRequest request) {
        TaskResponse result = client.post().uri("/tarefas")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().exists("Location")
                .expectBody(TaskResponse.class)
                .returnResult().getResponseBody();
        assertNotNull(result);
        return result;
    }

    private TaskResponse findThroughHttp(Long id) {
        TaskResponse result = client.get().uri("/tarefas/{id}", id)
                .exchange()
                .expectStatus().isOk()
                .expectBody(TaskResponse.class)
                .returnResult().getResponseBody();
        assertNotNull(result);
        return result;
    }

    private TaskResponse seedTask(TaskRequest request) {
        AtomicReference<TaskResponse> result = new AtomicReference<>();
        StepVerifier.create(tasks.save(TaskMapper.toTask(request)))
                .assertNext(task -> result.set(TaskMapper.toResponse(task)))
                .expectComplete().verify(Duration.ofSeconds(10));
        return result.get();
    }

    private void assertCounts(long expectedTasks, long expectedCommands) {
        Mono<Long> commandCount = database.sql(
                        "SELECT COUNT(*) AS total FROM processed_commands"
                )
                .map((row, metadata) -> row.get("total", Long.class))
                .one();
        StepVerifier.create(Mono.zip(tasks.count(), commandCount))
                .assertNext(counts -> {
                    assertEquals(expectedTasks, counts.getT1().longValue());
                    assertEquals(expectedCommands, counts.getT2().longValue());
                })
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    private TaskRequest request(String title) {
        return new TaskRequest(
                title, "Teste do fluxo de mensagens", Priority.MEDIA,
                Status.ABERTA, LocalDate.of(2026, 11, 30)
        );
    }
}