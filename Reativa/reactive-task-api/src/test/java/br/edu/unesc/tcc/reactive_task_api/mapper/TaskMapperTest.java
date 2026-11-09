package br.edu.unesc.tcc.reactive_task_api.mapper;

import br.edu.unesc.tcc.reactive_task_api.domain.Priority;
import br.edu.unesc.tcc.reactive_task_api.domain.Status;
import br.edu.unesc.tcc.reactive_task_api.domain.Task;
import br.edu.unesc.tcc.reactive_task_api.dto.TaskRequest;
import br.edu.unesc.tcc.reactive_task_api.dto.TaskResponse;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

public class TaskMapperTest {

    @Test
    void shouldCreateTaskWithoutId() {
        TaskRequest request = new TaskRequest(
                "Estudar Reactor",
                "Revisar os operadores",
                Priority.ALTA,
                Status.ABERTA,
                LocalDate.of(2020, 1, 15)
        );

        Task task = TaskMapper.toTask(request);

        assertAll(
                () -> assertNull(task.getId()),
                () -> assertEquals("Estudar Reactor", task.getTitulo()),
                () -> assertEquals("Revisar os operadores", task.getDescricao()),
                () -> assertEquals(Priority.ALTA, task.getPrioridade()),
                () -> assertEquals(Status.ABERTA, task.getStatus()),
                () -> assertEquals(
                        LocalDate.of(2020, 1, 15), task.getDataLimite()
                )
        );
    }

    @Test
    void shouldMapAllFieldsToResponse() {
        Task task = new Task();
        task.setId(42L);
        task.setTitulo("Preparar testes");
        task.setDescricao("Verificar o mapper");
        task.setPrioridade(Priority.MEDIA);
        task.setStatus(Status.EM_ANDAMENTO);
        task.setDataLimite(LocalDate.of(2020, 2, 20));

        TaskResponse response = TaskMapper.toResponse(task);

        TaskResponse expected = new TaskResponse(
                42L,
                "Preparar testes",
                "Verificar o mapper",
                Priority.MEDIA,
                Status.EM_ANDAMENTO,
                LocalDate.of(2020, 2, 20)
        );

        assertEquals(expected, response);
    }

    @Test
    void shouldReplaceMutableFieldsAndPreserveId() {
        Task task = new Task();
        task.setId(42L);
        task.setTitulo("Título anterior");
        task.setDescricao("Descrição anterior");
        task.setPrioridade(Priority.BAIXA);
        task.setStatus(Status.ABERTA);
        task.setDataLimite(LocalDate.of(2020, 1, 15));

        TaskRequest request = new TaskRequest(
                "Título atualizado",
                null,
                Priority.ALTA,
                Status.CONCLUIDA,
                LocalDate.of(2020, 3, 10)
        );

        TaskMapper.updateTask(task, request);

        assertAll(
                () -> assertEquals(Long.valueOf(42L), task.getId()),
                () -> assertEquals("Título atualizado", task.getTitulo()),
                () -> assertNull(task.getDescricao()),
                () -> assertEquals(Priority.ALTA, task.getPrioridade()),
                () -> assertEquals(Status.CONCLUIDA, task.getStatus()),
                () -> assertEquals(
                        LocalDate.of(2020, 3, 10), task.getDataLimite()
                )
        );
    }
}
