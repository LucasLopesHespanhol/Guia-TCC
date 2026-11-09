package br.edu.unesc.tcc.eda_task_api.events;

import br.edu.unesc.tcc.eda_task_api.config.RabbitConfig;
import br.edu.unesc.tcc.eda_task_api.service.TaskService;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

@Component
public class TaskEventListener {

    private static final Logger log = LoggerFactory.getLogger(TaskEventListener.class);
    private final TaskService taskService;
    private final JsonMapper jsonMapper;

    public TaskEventListener(TaskService taskService, JsonMapper jsonMapper) {
        this.taskService = taskService;
        this.jsonMapper = jsonMapper;
    }

    @RabbitListener(id = RabbitConfig.REQUESTS_LISTENER, queues = RabbitConfig.REQUESTS)
    public void onRegistration(Message message, Channel channel) throws IOException {
        TaskEvent event;
        try {
            event = readEvent(message);
            if (!event.type().isRegistration()) {
                throw new IllegalArgumentException("Esperado evento de registro de solicitação");
            }
        } catch (RuntimeException invalid) {
            reject(message, channel, invalid);
            return;
        }

        try {
            taskService.process(event);
        } catch (IllegalArgumentException invalid) {
            reject(message, channel, invalid);
            return;
        } catch (RuntimeException failure) {
            try {
                taskService.failRegistration(event, "PROCESSING_FAILED: " + failure.getClass().getSimpleName());
            } catch (RuntimeException recordingFailure) {
                log.error("Não foi possível registrar a falha da solicitação {}",
                        event.submissionId(), recordingFailure);
            }
            reject(message, channel, failure);
            return;
        }
        channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
    }

    @RabbitListener(id = RabbitConfig.FACTS_LISTENER, queues = RabbitConfig.FACTS)
    public void onTaskFact(Message message, Channel channel) throws IOException {
        try {
            TaskEvent event = readEvent(message);
            if (event.type().isRegistration() || event.taskId() == null || event.taskId() <= 0) {
                throw new IllegalArgumentException("Esperado fato de alteração de tarefa");
            }
            log.info("Fato observado: tipo={}, evento={}, solicitacao={}, tarefa={}, runId={}",
                    event.type(), event.eventId(), event.submissionId(), event.taskId(), event.runId());
        } catch (RuntimeException invalid) {
            reject(message, channel, invalid);
            return;
        }
        channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
    }

    private TaskEvent readEvent(Message message) {
        TaskEvent event = jsonMapper.readValue(message.getBody(), TaskEvent.class);
        if (event == null) {
            throw new IllegalArgumentException("Evento nulo");
        }
        event.validate();
        if (!event.eventId().toString().equals(message.getMessageProperties().getMessageId())) {
            throw new IllegalArgumentException("messageId não corresponde ao evento");
        }
        return event;
    }

    private void reject(Message message, Channel channel, RuntimeException reason) throws IOException {
        log.error("Evento rejeitado para DLQ: messageId={}",
                message.getMessageProperties().getMessageId(), reason);
        channel.basicReject(message.getMessageProperties().getDeliveryTag(), false);
    }
}