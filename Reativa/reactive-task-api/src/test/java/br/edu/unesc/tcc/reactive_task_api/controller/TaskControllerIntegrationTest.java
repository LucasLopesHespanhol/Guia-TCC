package br.edu.unesc.tcc.reactive_task_api.controller;

import org.springframework.r2dbc.core.DatabaseClient;
import br.edu.unesc.tcc.reactive_task_api.domain.Priority;
import br.edu.unesc.tcc.reactive_task_api.domain.Status;
import br.edu.unesc.tcc.reactive_task_api.dto.TaskRequest;
import br.edu.unesc.tcc.reactive_task_api.dto.TaskResponse;
import br.edu.unesc.tcc.reactive_task_api.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.messaging.command-queue=reactive.tasks.commands.http-test",
                "app.messaging.command-queue-capacity=100",
                "app.messaging.consumer-concurrency=1",
                "app.messaging.max-pending-requests=20",
                "spring.r2dbc.url=r2dbc:h2:mem:///http-test-${random.uuid}"
                        + "?options=DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
        }
)
class TaskControllerIntegrationTest {

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private DatabaseClient database;

    @Autowired
    private TaskRepository repository;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(5))
                .build();

        StepVerifier.create(database.sql("DELETE FROM processed_commands")
                        .fetch().rowsUpdated().then(repository.deleteAll()))
                .expectComplete()
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void shouldCompleteTaskLifecycle() {
        TaskResponse created = create(request("Estudar", "Inicial"));
        Long id = created.id();

        client.get().uri("/tarefas/{id}", id)
                .exchange()
                .expectStatus().isOk()
                .expectBody(TaskResponse.class)
                .isEqualTo(created);

        TaskRequest replacement = new TaskRequest(
                "Título atualizado",
                null,
                Priority.ALTA,
                Status.CONCLUIDA,
                LocalDate.of(2020, 3, 10)
        );

        TaskResponse expected = expectedResponse(id, replacement);

        client.put().uri("/tarefas/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(replacement)
                .exchange()
                .expectStatus().isOk()
                .expectBody(TaskResponse.class)
                .isEqualTo(expected);

        client.get().uri("/tarefas/{id}", id)
                .exchange()
                .expectStatus().isOk()
                .expectBody(TaskResponse.class)
                .isEqualTo(expected);

        client.delete().uri("/tarefas/{id}", id)
                .exchange()
                .expectStatus().isNoContent()
                .expectBody().isEmpty();

        client.get().uri("/tarefas/{id}", id)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.status").isEqualTo(404);
    }

    @Test
    void shouldReturnEmptyArray() {
        client.get().uri("/tarefas")
                .exchange()
                .expectStatus().isOk()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$").isArray()
                .jsonPath("$.length()").isEqualTo(0);
    }

    @Test
    void shouldReturnCompleteListByIdAscending() {
        TaskResponse first = create(request("Z — primeira", null));
        TaskResponse second = create(request("A — segunda", "Descrição"));

        assertTrue(first.id() < second.id());

        client.get().uri("/tarefas")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(TaskResponse.class)
                .isEqualTo(List.of(first, second));
    }

    @Test
    void shouldReturn404ForMissingTask() {
        client.get().uri("/tarefas/-1")
                .exchange()
                .expectStatus().isNotFound();

        client.put().uri("/tarefas/-1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request("Atualização", null))
                .exchange()
                .expectStatus().isNotFound();

        client.delete().uri("/tarefas/-1")
                .exchange()
                .expectStatus().isNotFound();
    }

    @ParameterizedTest(name = "Entrada inválida {index}")
    @MethodSource("invalidBodies")
    void shouldRejectInvalidInputWithoutChangingData(String body) {
        TaskResponse original = create(request("Estudar", "Inicial"));

        client.post().uri("/tarefas")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(400);

        client.get().uri("/tarefas")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(TaskResponse.class)
                .isEqualTo(List.of(original));

        client.put().uri("/tarefas/{id}", original.id())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.status").isEqualTo(400);

        client.get().uri("/tarefas/{id}", original.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(TaskResponse.class)
                .isEqualTo(original);
    }

    private TaskResponse create(TaskRequest request) {
        EntityExchangeResult<TaskResponse> result =
                client.post().uri("/tarefas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(request)
                        .exchange()
                        .expectStatus().isCreated()
                        .expectBody(TaskResponse.class)
                        .returnResult();

        TaskResponse response = result.getResponseBody();
        assertNotNull(response);
        assertNotNull(response.id());

        assertEquals(
                "/tarefas/" + response.id(),
                result.getResponseHeaders().getFirst("Location")
        );

        assertEquals(
                expectedResponse(response.id(), request),
                response
        );

        return response;
    }

    private static TaskRequest request(String title, String description) {
        return new TaskRequest(
                title,
                description,
                Priority.MEDIA,
                Status.ABERTA,
                LocalDate.of(2020, 1, 15)
        );
    }

    private static TaskResponse expectedResponse(
            Long id,
            TaskRequest request
    ) {
        return new TaskResponse(
                id,
                request.titulo(),
                request.descricao(),
                request.prioridade(),
                request.status(),
                request.dataLimite()
        );
    }

    private static Stream<String> invalidBodies() {
        String valid = """
                {
                  "titulo": "Estudar",
                  "descricao": "Inicial",
                  "prioridade": "MEDIA",
                  "status": "ABERTA",
                  "dataLimite": "2020-01-15"
                }
                """;

        return Stream.of(
                "{",
                valid.replace("\"Estudar\"", "null"),
                valid.replace("\"Estudar\"", "\"   \""),
                valid.replace(
                        "\"Estudar\"", "\"" + "a".repeat(121) + "\""
                ),
                valid.replace(
                        "\"Inicial\"", "\"" + "b".repeat(2001) + "\""
                ),
                valid.replace("\"MEDIA\"", "null"),
                valid.replace("\"ABERTA\"", "null"),
                valid.replace("\"2020-01-15\"", "null"),
                valid.replace("\"MEDIA\"", "\"URGENTE\""),
                valid.replace("\"ABERTA\"", "\"PENDENTE\""),
                valid.replace("\"MEDIA\"", "1"),
                valid.replace("\"2020-01-15\"", "\"2020-99-99\""),
                valid.replace("{", "{\"id\": 42,"),
                valid.replace("{", "{\"extra\": true,")
        );
    }
}