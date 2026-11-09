package br.edu.unesc.tcc.reactive_task_api.controller;

import br.edu.unesc.tcc.reactive_task_api.messaging.TaskCommandService;
import br.edu.unesc.tcc.reactive_task_api.messaging.TaskOperation;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import br.edu.unesc.tcc.reactive_task_api.domain.Priority;
import br.edu.unesc.tcc.reactive_task_api.domain.Status;
import br.edu.unesc.tcc.reactive_task_api.dto.TaskRequest;
import br.edu.unesc.tcc.reactive_task_api.dto.TaskResponse;
import br.edu.unesc.tcc.reactive_task_api.repository.TaskRepository;
import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.PoolMetrics;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Wrapped;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.messaging.command-queue=reactive.tasks.commands.pool-test",
                "app.messaging.command-queue-capacity=100",
                "app.messaging.consumer-concurrency=1",
                "app.messaging.max-pending-requests=20",
                "spring.r2dbc.pool.max-acquire-time=2s",
                "spring.r2dbc.url=r2dbc:h2:mem:///pool-test-${random.uuid}"
                        + "?options=DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
        }
)
public class TaskControllerPoolSaturationTest {

    private static final Duration VERIFY_TIMEOUT = Duration.ofSeconds(10);

    @Value("${local.server.port}")
    private int port;

    @Value("${spring.r2dbc.pool.max-size}")
    private int maxConnections;

    @Autowired
    private DatabaseClient database;

    @MockitoSpyBean
    private TaskCommandService commandService;

    @Autowired
    private ConnectionFactory connectionFactory;

    @Autowired
    private TaskRepository repository;

    private final List<Connection> heldConnections = new ArrayList<>();

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(5))
                .build();

        StepVerifier.create(repository.deleteAll())
                .expectComplete()
                .verify(VERIFY_TIMEOUT);
    }

    @AfterEach
    void cleanUp() {
        releaseConnections();
    }

    @Test
    void shouldRejectWhenPoolIsExhaustedAndRecover() {
        // Desde 03/11/2026 o pool e entregue dentro de um envoltorio (R2dbcConcurrencyConfig).
        ConnectionFactory target = connectionFactory instanceof Wrapped<?> wrapped
                ? (ConnectionFactory) wrapped.unwrap()
                : connectionFactory;
        ConnectionPool pool =
                assertInstanceOf(ConnectionPool.class, target);

        PoolMetrics metrics = pool.getMetrics()
                .orElseThrow(() ->
                        new AssertionError("Métricas do pool indisponíveis")
                );

        assertEquals(maxConnections, metrics.getMaxAllocatedSize());

        StepVerifier.create(
                        Flux.range(0, maxConnections)
                                .concatMap(index ->
                                        Mono.from(connectionFactory.create())
                                )
                                .doOnNext(heldConnections::add)
                                .then()
                )
                .expectComplete()
                .verify(VERIFY_TIMEOUT);

        assertEquals(maxConnections, heldConnections.size());
        assertEquals(maxConnections, metrics.acquiredSize());

        client.get().uri("/tarefas")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.status").isEqualTo(503);

        client.post().uri("/tarefas")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request())
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.status").isEqualTo(503);

        // Desde 03/11/2026 a listagem le o banco diretamente (CQRS) e nao gera comando.
        verify(commandService, never()).execute(argThat(command -> command != null
                && command.operation() == TaskOperation.FIND_ALL));
        verify(commandService).execute(argThat(command -> command != null
                && command.operation() == TaskOperation.CREATE));

        releaseConnections();

        StepVerifier.create(repository.count())
                .expectNext(0L)
                .expectComplete()
                .verify(VERIFY_TIMEOUT);

        TaskResponse created = client.post().uri("/tarefas")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request())
                .exchange()
                .expectStatus().isCreated()
                .expectBody(TaskResponse.class)
                .returnResult()
                .getResponseBody();

        assertNotNull(created);
        assertNotNull(created.id());

        client.get().uri("/tarefas/{id}", created.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(TaskResponse.class)
                .isEqualTo(created);
    }

    private void releaseConnections() {
        if (heldConnections.isEmpty()) {
            return;
        }

        StepVerifier.create(
                        Flux.fromIterable(new ArrayList<>(heldConnections))
                                .concatMap(connection ->
                                        Mono.from(connection.close())
                                )
                                .then()
                )
                .expectComplete()
                .verify(VERIFY_TIMEOUT);

        heldConnections.clear();
    }

    private static TaskRequest request() {
        return new TaskRequest(
                "Teste de recuperação",
                null,
                Priority.MEDIA,
                Status.ABERTA,
                LocalDate.of(2020, 1, 15)
        );
    }
}