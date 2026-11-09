package br.edu.unesc.tcc.reactive_task_api.dto;

import br.edu.unesc.tcc.reactive_task_api.domain.Priority;
import br.edu.unesc.tcc.reactive_task_api.domain.Status;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class TaskRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    @Test
    void shouldAcceptNullDescriptionAndPastDate() {
        TaskRequest request = request("Estudar", null);

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void shouldAcceptMaximumLengths() {
        TaskRequest request = request(
                "a".repeat(120),
                "b".repeat(2000)
        );

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void shouldRejectMissingOrBlankTitle() {
        String[] titles = {null, "", "   ", "\t"};

        for (String title : titles) {
            assertEquals(
                    Set.of("titulo"),
                    invalidFields(request(title, null)),
                    "Deveria rejeitar título: " + title
            );
        }
    }

    @Test
    void shouldRejectLengthsAboveLimits() {
        assertAll(
                () -> assertEquals(
                        Set.of("titulo"),
                        invalidFields(request("a".repeat(121), null))
                ),
                () -> assertEquals(
                        Set.of("descricao"),
                        invalidFields(request("Estudar", "b".repeat(2001)))
                )
        );
    }

    @Test
    void shouldRequirePriorityStatusAndDeadline() {
        TaskRequest request = new TaskRequest(
                "Estudar",
                null,
                null,
                null,
                null
        );

        assertEquals(
                Set.of("prioridade", "status", "dataLimite"),
                invalidFields(request)
        );
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

    private static Set<String> invalidFields(TaskRequest request) {
        return validator.validate(request).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }
}
