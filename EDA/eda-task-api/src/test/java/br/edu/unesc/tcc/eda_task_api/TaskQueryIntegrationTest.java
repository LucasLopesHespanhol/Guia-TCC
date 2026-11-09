package br.edu.unesc.tcc.eda_task_api;

import br.edu.unesc.tcc.eda_task_api.domain.Priority;
import br.edu.unesc.tcc.eda_task_api.domain.Status;
import br.edu.unesc.tcc.eda_task_api.domain.Task;
import br.edu.unesc.tcc.eda_task_api.dto.TaskRequest;
import br.edu.unesc.tcc.eda_task_api.mapper.TaskMapper;
import br.edu.unesc.tcc.eda_task_api.repository.TaskRepository;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import br.edu.unesc.tcc.eda_task_api.domain.SubmissionStatus;
import br.edu.unesc.tcc.eda_task_api.events.TaskEvent;
import br.edu.unesc.tcc.eda_task_api.events.TaskEventPublisher;
import br.edu.unesc.tcc.eda_task_api.repository.SubmissionRepository;
import br.edu.unesc.tcc.eda_task_api.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDate;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:eda-query-check;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.rabbitmq.password=unused",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.dynamic=false"
})
@AutoConfigureMockMvc
class TaskQueryIntegrationTest {

    @MockitoBean
    private TaskEventPublisher publisher;

    @BeforeEach
    void clearDatabase(@Autowired TaskRepository tasks, @Autowired SubmissionRepository submissions) {
        submissions.deleteAll();
        tasks.deleteAll();
    }


    @Test
    void shouldPersistAndQueryTaskContract(@Autowired MockMvc mvc,
                                           @Autowired TaskRepository repository,
                                           @Autowired Validator validator) throws Exception {
        TaskRequest firstRequest = new TaskRequest(
                "a".repeat(120), null, Priority.BAIXA, Status.ABERTA,
                LocalDate.of(2020, 1, 1));

        TaskRequest secondRequest = new TaskRequest(
                "Segunda tarefa", "b".repeat(2000), Priority.ALTA,
                Status.CONCLUIDA, LocalDate.of(2026, 12, 15));

        assertTrue(validator.validate(firstRequest).isEmpty());
        assertTrue(validator.validate(secondRequest).isEmpty());

        TaskRequest invalidRequest = new TaskRequest(
                "Descrição excedente", "b".repeat(2001), Priority.MEDIA,
                Status.EM_ANDAMENTO, LocalDate.of(2026, 12, 15));

        var violations = validator.validate(invalidRequest);
        assertEquals(1, violations.size());
        assertEquals("descricao",
                violations.iterator().next().getPropertyPath().toString());

        Task first = repository.saveAndFlush(TaskMapper.toTask(firstRequest));
        Task second = repository.saveAndFlush(TaskMapper.toTask(secondRequest));

        assertTrue(first.getId() < second.getId());

        mvc.perform(get("/tarefas/{id}", second.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$.id").value(second.getId()))
                .andExpect(jsonPath("$.titulo").value(secondRequest.titulo()))
                .andExpect(jsonPath("$.descricao").value(secondRequest.descricao()))
                .andExpect(jsonPath("$.prioridade").value("ALTA"))
                .andExpect(jsonPath("$.status").value("CONCLUIDA"))
                .andExpect(jsonPath("$.dataLimite").value("2026-12-15"));

        mvc.perform(get("/tarefas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(first.getId()))
                .andExpect(jsonPath("$[1].id").value(second.getId()))
                .andExpect(jsonPath("$[0].titulo").value(firstRequest.titulo()))
                .andExpect(jsonPath("$[0].descricao").value(nullValue()))
                .andExpect(jsonPath("$[0].dataLimite").value("2020-01-01"))
                .andExpect(jsonPath("$[1].descricao").value(secondRequest.descricao()));
    }

    @Test
    void shouldProcessOnceAndPreserveTerminalResult(@Autowired TaskService service,
                                                   @Autowired TaskRepository tasks) {
        var request = new TaskRequest("Criar", "Descrição", Priority.MEDIA, Status.ABERTA,
                LocalDate.of(2020, 1, 1));
        var accepted = service.registerCreate(request);
        assertEquals(SubmissionStatus.REGISTRADA, accepted.estado());
        assertEquals(0, tasks.count());
        var event = new TaskEvent(accepted.id(), accepted.id(), accepted.tipo(),
                accepted.registradaEm(), null, 1, "teste");
        service.process(event);
        var completed = service.findSubmission(accepted.id()).orElseThrow();
        assertEquals(SubmissionStatus.CONCLUIDA, completed.estado());
        service.process(event);
        service.failRegistration(event, "Falha tardia não pode desfazer conclusão");
        assertEquals(completed, service.findSubmission(accepted.id()).orElseThrow());
        assertEquals(1, tasks.count());
    }

    @Test
    void shouldRejectMissingTaskAndNeverReopenFailure(@Autowired TaskService service,
                                                    @Autowired TaskRepository tasks) {
        var missing = service.registerDelete(999L);
        var missingEvent = new TaskEvent(missing.id(), missing.id(), missing.tipo(),
                missing.registradaEm(), 999L, 1, "teste");
        service.process(missingEvent);
        var rejected = service.findSubmission(missing.id()).orElseThrow();
        assertEquals(SubmissionStatus.REJEITADA, rejected.estado());
        assertTrue(rejected.erro().startsWith("NOT_FOUND"));
        assertNull(rejected.resultado());

        var accepted = service.registerCreate(new TaskRequest("Não executar", null,
                Priority.BAIXA, Status.ABERTA, LocalDate.of(2020, 1, 1)));
        var event = new TaskEvent(accepted.id(), accepted.id(), accepted.tipo(),
                accepted.registradaEm(), null, 1, "teste");
        service.failRegistration(event, "PUBLICATION_FAILED");
        var failed = service.findSubmission(accepted.id()).orElseThrow();
        service.process(event);
        service.failRegistration(event, "Não substituir o erro original");
        assertEquals(SubmissionStatus.FALHA, failed.estado());
        assertEquals(failed, service.findSubmission(accepted.id()).orElseThrow());
        assertEquals(0, tasks.count());
    }
}
