package br.edu.unesc.tcc.reactive_task_api.repository;

import br.edu.unesc.tcc.reactive_task_api.domain.Priority;
import br.edu.unesc.tcc.reactive_task_api.domain.Status;
import br.edu.unesc.tcc.reactive_task_api.domain.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
                "spring.r2dbc.url=r2dbc:h2:mem:///repository-test-${random.uuid}"
                        + "?options=DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
        }
)
public class TaskRepositoryIntegrationTest {

    private static final Duration VERIFY_TIMEOUT = Duration.ofSeconds(5);

    @Autowired
    private TaskRepository repository;

    @BeforeEach
    void cleanDatabase() {
        StepVerifier.create(repository.deleteAll())
                .expectComplete()
                .verify(VERIFY_TIMEOUT);
    }

    @Test
    void shouldSaveAndReadAllFields() {
        Task task = newTask("Estudar Reactor", "Revisar operadores");

        Mono<Task> operation = repository.save(task)
                .flatMap(saved -> {
                    assertNotNull(saved.getId());

                    return repository.findById(saved.getId())
                            .switchIfEmpty(Mono.error(
                                    new AssertionError("Tarefa salva não encontrada")
                            ));
                });

        StepVerifier.create(operation)
                .assertNext(loaded -> {
                    assertNotNull(loaded.getId());
                    assertFields(
                            loaded,
                            "Estudar Reactor",
                            "Revisar operadores",
                            Priority.MEDIA,
                            Status.ABERTA,
                            LocalDate.of(2020, 1, 15)
                    );
                })
                .expectComplete()
                .verify(VERIFY_TIMEOUT);
    }

    @Test
    void shouldUpdateAndDeleteTask() {
        Task task = newTask("Título anterior", "Descrição anterior");

        Mono<Boolean> operation = repository.save(task)
                .flatMap(saved -> {
                    Long id = saved.getId();
                    assertNotNull(id);

                    saved.setTitulo("Título atualizado");
                    saved.setDescricao(null);
                    saved.setPrioridade(Priority.ALTA);
                    saved.setStatus(Status.CONCLUIDA);
                    saved.setDataLimite(LocalDate.of(2020, 3, 10));

                    return repository.save(saved)
                            .then(repository.findById(id))
                            .switchIfEmpty(Mono.error(
                                    new AssertionError("Tarefa atualizada não encontrada")
                            ))
                            .doOnNext(loaded -> {
                                assertEquals(id, loaded.getId());
                                assertFields(
                                        loaded,
                                        "Título atualizado",
                                        null,
                                        Priority.ALTA,
                                        Status.CONCLUIDA,
                                        LocalDate.of(2020, 3, 10)
                                );
                            })
                            .flatMap(loaded ->
                                    repository.deleteById(id)
                                            .then(repository.findById(id))
                                            .hasElement()
                            );
                });

        StepVerifier.create(operation)
                .assertNext(exists -> assertFalse(exists))
                .expectComplete()
                .verify(VERIFY_TIMEOUT);
    }

    @Test
    void shouldListTasksByIdAscending() {
        Task first = newTask("Z — primeira", null);
        Task second = newTask("A — segunda", "Descrição");

        Mono<java.util.List<Task>> operation = Flux.concat(
                        repository.save(first),
                        repository.save(second)
                )
                .thenMany(repository.findAllByOrderByIdAsc())
                .collectList();

        StepVerifier.create(operation)
                .assertNext(tasks -> {
                    assertEquals(2, tasks.size());

                    Task firstResult = tasks.get(0);
                    Task secondResult = tasks.get(1);

                    assertAll(
                            () -> assertNotNull(firstResult.getId()),
                            () -> assertNotNull(secondResult.getId()),
                            () -> assertTrue(
                                    firstResult.getId() < secondResult.getId()
                            ),
                            () -> assertEquals(
                                    "Z — primeira", firstResult.getTitulo()
                            ),
                            () -> assertEquals(
                                    "A — segunda", secondResult.getTitulo()
                            )
                    );
                })
                .expectComplete()
                .verify(VERIFY_TIMEOUT);
    }

    private static Task newTask(String title, String description) {
        Task task = new Task();
        task.setTitulo(title);
        task.setDescricao(description);
        task.setPrioridade(Priority.MEDIA);
        task.setStatus(Status.ABERTA);
        task.setDataLimite(LocalDate.of(2020, 1, 15));
        return task;
    }

    private static void assertFields(
            Task task,
            String title,
            String description,
            Priority priority,
            Status status,
            LocalDate deadline
    ) {
        assertAll(
                () -> assertEquals(title, task.getTitulo()),
                () -> assertEquals(description, task.getDescricao()),
                () -> assertEquals(priority, task.getPrioridade()),
                () -> assertEquals(status, task.getStatus()),
                () -> assertEquals(deadline, task.getDataLimite())
        );
    }
}