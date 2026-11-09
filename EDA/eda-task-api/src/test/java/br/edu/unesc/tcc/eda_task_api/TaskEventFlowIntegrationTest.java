package br.edu.unesc.tcc.eda_task_api;

import br.edu.unesc.tcc.eda_task_api.config.RabbitConfig;
import br.edu.unesc.tcc.eda_task_api.domain.SubmissionStatus;
import br.edu.unesc.tcc.eda_task_api.dto.SubmissionResponse;
import br.edu.unesc.tcc.eda_task_api.events.TaskEvent;
import br.edu.unesc.tcc.eda_task_api.events.TaskEventPublisher;
import br.edu.unesc.tcc.eda_task_api.events.TaskEventType;
import br.edu.unesc.tcc.eda_task_api.repository.SubmissionRepository;
import br.edu.unesc.tcc.eda_task_api.repository.TaskRepository;
import br.edu.unesc.tcc.eda_task_api.service.TaskService;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fluxo completo com RabbitMQ real, em vhost exclusivo de teste (eda-test), H2 isolado e
 * um consumidor (ordem FIFO, para tornar determinista o caso de reentrega).
 * Pre-requisitos: broker da EDA ligado, vhost eda-test com permissao para o usuario tcc
 * e RABBITMQ_PASSWORD definida na execucao. As filas do vhost de teste sao esvaziadas antes de cada caso.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:eda-flow-${random.uuid};DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.rabbitmq.virtual-host=eda-test",
        "spring.rabbitmq.listener.simple.concurrency=1",
        "spring.rabbitmq.listener.simple.max-concurrency=1",
        "eda.run-id=teste-integracao"
})
@AutoConfigureMockMvc
class TaskEventFlowIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final String VALID = json("\"Tarefa EDA\"", "\"Descricao\"", "\"MEDIA\"", "\"ABERTA\"", "\"2026-12-15\"", "");
    private static final String UPDATED = json("\"Tarefa alterada\"", "null", "\"ALTA\"", "\"CONCLUIDA\"", "\"2027-01-10\"", "");

    @Autowired private MockMvc mvc;
    @Autowired private TaskService service;
    @Autowired private TaskRepository tasks;
    @Autowired private SubmissionRepository submissions;
    @Autowired private TaskEventPublisher publisher;
    @Autowired private RabbitTemplate rabbit;
    @Autowired private AmqpAdmin admin;
    @Autowired private MeterRegistry meters;

    @BeforeEach
    void cleanState() {
        for (String queue : new String[]{RabbitConfig.REQUESTS, RabbitConfig.FACTS,
                RabbitConfig.REQUESTS + ".dlq", RabbitConfig.FACTS + ".dlq"}) {
            admin.purgeQueue(queue, false);
        }
        submissions.deleteAll();
        tasks.deleteAll();
    }

    @Test
    void crudThroughBrokerReachesCompletion() throws Exception {
        SubmissionResponse created = awaitTerminal(accept(post("/tarefas"), VALID));
        assertEquals(SubmissionStatus.CONCLUIDA, created.estado());
        assertNull(created.erro());
        Long id = created.tarefaId();
        assertEquals(id, created.resultado().id());
        assertEquals("Tarefa EDA", created.resultado().titulo());

        mvc.perform(get("/tarefas/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$.titulo").value("Tarefa EDA"))
                .andExpect(jsonPath("$.descricao").value("Descricao"))
                .andExpect(jsonPath("$.prioridade").value("MEDIA"))
                .andExpect(jsonPath("$.status").value("ABERTA"))
                .andExpect(jsonPath("$.dataLimite").value("2026-12-15"));
        mvc.perform(get("/tarefas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(id));

        SubmissionResponse updated = awaitTerminal(accept(put("/tarefas/{id}", id), UPDATED));
        assertEquals(SubmissionStatus.CONCLUIDA, updated.estado());
        assertEquals(id, updated.tarefaId());
        mvc.perform(get("/tarefas/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.titulo").value("Tarefa alterada"))
                .andExpect(jsonPath("$.descricao").value(nullValue()))
                .andExpect(jsonPath("$.prioridade").value("ALTA"))
                .andExpect(jsonPath("$.status").value("CONCLUIDA"))
                .andExpect(jsonPath("$.dataLimite").value("2027-01-10"));

        SubmissionResponse deleted = awaitTerminal(accept(delete("/tarefas/{id}", id), null));
        assertEquals(SubmissionStatus.CONCLUIDA, deleted.estado());
        assertEquals(id, deleted.tarefaId());
        mvc.perform(get("/tarefas/{id}", id)).andExpect(status().isNotFound());
        mvc.perform(get("/solicitacoes/{id}", deleted.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("CONCLUIDA"))
                .andExpect(jsonPath("$.tipo").value("DELETION_REQUEST_REGISTERED"));

        // Os tres fatos de dominio foram consumidos pelo observador, sem falhas.
        await(() -> ready(RabbitConfig.FACTS) == 0, "fila de fatos nao foi drenada");
        assertEquals(0, ready(RabbitConfig.FACTS + ".dlq"));
        assertEquals(0, ready(RabbitConfig.REQUESTS + ".dlq"));

        // Metricas do protocolo: nenhuma pendencia e tres conclusoes registradas apos commit.
        await(() -> meters.get("eda.submissions.registered").gauge().value() == 0, "pendencias nao zeraram");
        long completed = meters.find("eda.submission.completion").tag("estado", "CONCLUIDA")
                .timers().stream().mapToLong(Timer::count).sum();
        assertTrue(completed >= 3, "esperado ao menos tres conclusoes no timer");
    }

    @Test
    void missingTargetIsRejectedByConsumer() throws Exception {
        SubmissionResponse update = awaitTerminal(accept(put("/tarefas/{id}", 999_999L), VALID));
        SubmissionResponse removal = awaitTerminal(accept(delete("/tarefas/{id}", 999_999L), null));
        for (SubmissionResponse rejected : new SubmissionResponse[]{update, removal}) {
            assertEquals(SubmissionStatus.REJEITADA, rejected.estado());
            assertTrue(rejected.erro().startsWith("NOT_FOUND"));
            assertNull(rejected.resultado());
        }
        assertEquals(0, tasks.count());
        assertEquals(0, ready(RabbitConfig.REQUESTS + ".dlq"));
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void invalidInputIsRejectedBeforeRegistration(String body) throws Exception {
        mvc.perform(post("/tarefas").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/tarefas/{id}", 1L).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        assertEquals(0, submissions.count());
        assertEquals(0, tasks.count());
    }

    static Stream<String> invalidBodies() {
        return Stream.of(
                json("\" \"", "null", "\"MEDIA\"", "\"ABERTA\"", "\"2026-12-15\"", ""),
                json("null", "null", "\"MEDIA\"", "\"ABERTA\"", "\"2026-12-15\"", ""),
                json("\"" + "a".repeat(121) + "\"", "null", "\"MEDIA\"", "\"ABERTA\"", "\"2026-12-15\"", ""),
                json("\"Tarefa\"", "\"" + "b".repeat(2001) + "\"", "\"MEDIA\"", "\"ABERTA\"", "\"2026-12-15\"", ""),
                json("\"Tarefa\"", "null", "null", "\"ABERTA\"", "\"2026-12-15\"", ""),
                json("\"Tarefa\"", "null", "\"MEDIA\"", "null", "\"2026-12-15\"", ""),
                json("\"Tarefa\"", "null", "\"MEDIA\"", "\"ABERTA\"", "null", ""),
                json("\"Tarefa\"", "null", "\"URGENTE\"", "\"ABERTA\"", "\"2026-12-15\"", ""),
                json("\"Tarefa\"", "null", "\"MEDIA\"", "\"FEITA\"", "\"2026-12-15\"", ""),
                json("\"Tarefa\"", "null", "\"MEDIA\"", "\"ABERTA\"", "\"2026-02-30\"", ""),
                json("\"Tarefa\"", "null", "\"MEDIA\"", "\"ABERTA\"", "\"15/12/2026\"", ""),
                json("\"Tarefa\"", "null", "1", "\"ABERTA\"", "\"2026-12-15\"", ""),
                json("\"Tarefa\"", "null", "\"MEDIA\"", "\"ABERTA\"", "\"2026-12-15\"", ",\"extra\":\"x\""),
                json("\"Tarefa\"", "null", "\"MEDIA\"", "\"ABERTA\"", "\"2026-12-15\"", ",\"id\":5"),
                "{\"titulo\":");
    }

    @Test
    void malformedIdentifiersReturnBadRequest() throws Exception {
        mvc.perform(get("/tarefas/{id}", "abc")).andExpect(status().isBadRequest());
        mvc.perform(get("/solicitacoes/{id}", "nao-e-uuid")).andExpect(status().isBadRequest());
        mvc.perform(get("/solicitacoes/{id}", UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void redeliveredRegistrationDoesNotRepeatEffect() throws Exception {
        SubmissionResponse first = awaitTerminal(accept(post("/tarefas"), VALID));
        assertEquals(SubmissionStatus.CONCLUIDA, first.estado());

        // Mesmo evento de registro (mesmo messageId) entregue novamente pelo broker.
        publisher.publishAfterCommit(new TaskEvent(first.id(), first.id(), first.tipo(),
                first.registradaEm(), null, 1, "teste-integracao"));
        // Com um consumidor, a fila e FIFO: concluir um novo pedido implica que a reentrega ja foi tratada.
        SubmissionResponse second = awaitTerminal(accept(post("/tarefas"), VALID));
        assertEquals(SubmissionStatus.CONCLUIDA, second.estado());

        assertEquals(2, tasks.count());
        assertEquals(first, service.findSubmission(first.id()).orElseThrow());
        assertEquals(0, ready(RabbitConfig.REQUESTS + ".dlq"));
    }

    @Test
    void invalidAndOrphanEventsGoToDeadLetterQueue() throws Exception {
        var invalid = MessageBuilder.withBody("{}".getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setMessageId(UUID.randomUUID().toString())
                .build();
        rabbit.send(RabbitConfig.EVENTS, TaskEventType.CREATION_REQUEST_REGISTERED.routingKey(), invalid);

        UUID orphan = UUID.randomUUID();
        publisher.publishAfterCommit(new TaskEvent(orphan, orphan, TaskEventType.CREATION_REQUEST_REGISTERED,
                Instant.now(), null, 1, "teste-integracao"));

        await(() -> ready(RabbitConfig.REQUESTS + ".dlq") == 2, "eventos nao chegaram a DLQ");
        assertEquals(0, tasks.count());
        assertEquals(0, submissions.count());
    }

    private UUID accept(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                        String body) throws Exception {
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        ResultActions result = mvc.perform(request)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.estado").value("REGISTRADA"));
        String content = result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        UUID id = UUID.fromString(JsonPath.read(content, "$.id"));
        result.andExpect(header().string("Location", "/solicitacoes/" + id));
        return id;
    }

    private SubmissionResponse awaitTerminal(UUID id) throws InterruptedException {
        SubmissionResponse[] last = new SubmissionResponse[1];
        await(() -> {
            last[0] = service.findSubmission(id).orElseThrow();
            return last[0].estado() != SubmissionStatus.REGISTRADA;
        }, "solicitacao " + id + " permaneceu REGISTRADA");
        return last[0];
    }

    private long ready(String queue) {
        QueueInformation info = admin.getQueueInfo(queue);
        return info == null ? -1 : info.getMessageCount();
    }

    private static void await(BooleanSupplier condition, String message) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail(message);
            }
            Thread.sleep(50);
        }
    }

    private static String json(String titulo, String descricao, String prioridade,
                               String status, String dataLimite, String extra) {
        return "{\"titulo\":" + titulo + ",\"descricao\":" + descricao + ",\"prioridade\":" + prioridade
                + ",\"status\":" + status + ",\"dataLimite\":" + dataLimite + extra + "}";
    }
}
