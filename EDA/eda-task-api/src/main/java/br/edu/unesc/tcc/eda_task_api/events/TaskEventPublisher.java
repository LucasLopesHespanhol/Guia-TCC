package br.edu.unesc.tcc.eda_task_api.events;

import br.edu.unesc.tcc.eda_task_api.config.RabbitConfig;
import br.edu.unesc.tcc.eda_task_api.service.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.TimeUnit;

@Component
public class TaskEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(TaskEventPublisher.class);
    private static final long CONFIRM_TIMEOUT_SECONDS = 30;
    private final RabbitTemplate rabbitTemplate;
    private final JsonMapper jsonMapper;
    private final TaskService taskService;

    public TaskEventPublisher(RabbitTemplate rabbitTemplate, JsonMapper jsonMapper,
                              TaskService taskService) {
        this.rabbitTemplate = rabbitTemplate;
        this.jsonMapper = jsonMapper;
        this.taskService = taskService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publishAfterCommit(TaskEvent event) {
        CorrelationData correlation;
        try {
            event.validate();
            var message = MessageBuilder.withBody(jsonMapper.writeValueAsBytes(event))
                    .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                    .setContentEncoding("UTF-8")
                    .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                    .setMessageId(event.eventId().toString())
                    .setCorrelationId(event.submissionId().toString())
                    .build();
            correlation = new CorrelationData(event.eventId().toString());
            rabbitTemplate.send(RabbitConfig.EVENTS, event.type().routingKey(), message, correlation);
        } catch (RuntimeException failure) {
            handleFailure(event, failure);
            return;
        }

        correlation.getFuture()
                .orTimeout(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenCompleteAsync((confirm, failure) -> {
                    if (failure != null) {
                        handleFailure(event, failure);
                    } else if (!confirm.isAck() || correlation.getReturned() != null) {
                        handleFailure(event, new IllegalStateException(
                                "Publicação sem confirmação positiva ou sem destino"));
                    } else {
                        log.info("Evento publicado: tipo={}, evento={}, solicitacao={}",
                                event.type(), event.eventId(), event.submissionId());
                    }
                });
    }

    private void handleFailure(TaskEvent event, Throwable failure) {
        log.error("Falha de publicação: tipo={}, evento={}, solicitacao={}",
                event.type(), event.eventId(), event.submissionId(), failure);
        if (event.type().isRegistration()) {
            try {
                taskService.failRegistration(event, "PUBLICATION_FAILED: " + failure.getClass().getSimpleName());
            } catch (RuntimeException recordingFailure) {
                log.error("Não foi possível registrar a falha da solicitação {}",
                        event.submissionId(), recordingFailure);
            }
        }
    }
}
