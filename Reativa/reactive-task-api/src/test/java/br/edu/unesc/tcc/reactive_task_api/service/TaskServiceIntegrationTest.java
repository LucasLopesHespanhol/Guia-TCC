package br.edu.unesc.tcc.reactive_task_api.service;

import io.r2dbc.spi.R2dbcException;
import br.edu.unesc.tcc.reactive_task_api.domain.Priority;
import br.edu.unesc.tcc.reactive_task_api.domain.Status;
import br.edu.unesc.tcc.reactive_task_api.dto.TaskRequest;
import br.edu.unesc.tcc.reactive_task_api.dto.TaskResponse;
import br.edu.unesc.tcc.reactive_task_api.exception.TaskNotFoundException;
import br.edu.unesc.tcc.reactive_task_api.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.rabbitmq.listener.simple.auto-startup=false",
                "spring.r2dbc.url=r2dbc:h2:mem:///service-test-${random.uuid}"
                        + "?options=DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
        }
)
public class TaskServiceIntegrationTest {

    private static final Duration VERIFY_TIMEOUT = Duration.ofSeconds(5);

    @Autowired
    private TaskService service;

    @Autowired
    private TaskRepository repository;

    enum MissingOperation {
        READ,
        UPDATE,
        DELETE
    }

    @BeforeEach
    void cleanDatabase() {
        StepVerifier.create(repository.deleteAll())
                .expectComplete()
                .verify(VERIFY_TIMEOUT);
    }

    @Test
    void shouldCreateReadUpdateAndDelete() {
        TaskRequest original = request("Título original", "Descrição original");

        TaskRequest replacement = new TaskRequest(
                "Título atualizado",
                null,
                Priority.ALTA,
                Status.CONCLUIDA,
                LocalDate.of(2020, 3, 10)
        );

        Mono<Boolean> operation = service.create(original)
                .flatMap(created -> {
                    Long id = created.id();
                    assertNotNull(id);
                    assertEquals(expectedResponse(id, original), created);

                    TaskResponse expectedUpdated =
                            expectedResponse(id, replacement);

                    return service.findById(id)
                            .doOnNext(found -> assertEquals(created, found))
                            .then(service.update(id, replacement))
                            .doOnNext(updated ->
                                    assertEquals(expectedUpdated, updated)
                            )
                            .flatMap(updated -> service.findById(id))
                            .doOnNext(found ->
                                    assertEquals(expectedUpdated, found)
                            )
                            .flatMap(found ->
                                    service.delete(id)
                                            .then(repository.existsById(id))
                            );
                });

        StepVerifier.create(operation)
                .expectNext(false)
                .expectComplete()
                .verify(VERIFY_TIMEOUT);
    }

    @Test
    void shouldReturnCompleteListByIdAscending() {
        Mono<java.util.List<TaskResponse>> operation = Flux.concat(
                        service.create(request("Z — primeira", null)),
                        service.create(request("A — segunda", "Descrição"))
                )
                .then(service.findAll());

        StepVerifier.create(operation)
                .assertNext(tasks -> {
                    assertEquals(2, tasks.size());

                    assertAll(
                            () -> assertTrue(
                                    tasks.get(0).id() < tasks.get(1).id()
                            ),
                            () -> assertEquals(
                                    "Z — primeira", tasks.get(0).titulo()
                            ),
                            () -> assertEquals(
                                    "A — segunda", tasks.get(1).titulo()
                            )
                    );
                })
                .expectComplete()
                .verify(VERIFY_TIMEOUT);
    }

    @ParameterizedTest
    @EnumSource(MissingOperation.class)
    void shouldRejectMissingTask(MissingOperation operation) {
        Mono<?> result = switch (operation) {
            case READ -> service.findById(-1L);
            case UPDATE -> service.update(
                    -1L, request("Atualização", null)
            );
            case DELETE -> service.delete(-1L);
        };

        StepVerifier.create(result)
                .expectError(TaskNotFoundException.class)
                .verify(VERIFY_TIMEOUT);
    }

    @Test
    void shouldPreserveOriginalTaskWhenUpdateFails() {
        TaskRequest original = request("Título original", "Descrição original");

        TaskRequest invalid = new TaskRequest(
                "a".repeat(121),
                null,
                Priority.ALTA,
                Status.CONCLUIDA,
                LocalDate.of(2020, 3, 10)
        );

        Mono<TaskResponse> operation = service.create(original)
                .flatMap(created ->
                        service.update(created.id(), invalid)
                                .then(Mono.<TaskResponse>error(
                                        new AssertionError(
                                                "A atualização deveria falhar"
                                        )
                                ))
                                .onErrorResume(
                                        TaskServiceIntegrationTest::isLengthViolation,
                                        error -> service.findById(created.id())
                                                .doOnNext(restored ->
                                                        assertEquals(
                                                                created,
                                                                restored
                                                        )
                                                )
                                )
                );

        StepVerifier.create(operation)
                .assertNext(restored ->
                        assertEquals(
                                expectedResponse(restored.id(), original),
                                restored
                        )
                )
                .expectComplete()
                .verify(VERIFY_TIMEOUT);
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

    private static boolean isLengthViolation(Throwable error) {
        for (Throwable current = error;
             current != null;
             current = current.getCause()) {

            if (current instanceof R2dbcException exception
                    && "22001".equals(exception.getSqlState())) {
                return true;
            }
        }

        return false;
    }
}