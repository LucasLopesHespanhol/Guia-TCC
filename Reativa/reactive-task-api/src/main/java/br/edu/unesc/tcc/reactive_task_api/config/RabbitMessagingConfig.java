package br.edu.unesc.tcc.reactive_task_api.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.rabbit.config.ContainerCustomizer;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.beans.factory.annotation.Value;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

@Configuration(proxyBeanMethods = false)
public class RabbitMessagingConfig {

    public static final String COMMAND_QUEUE = "reactive.tasks.commands";

    @Bean
    public Queue taskCommandsQueue(
            @Value("${app.messaging.command-queue:reactive.tasks.commands}") String name,
            @Value("${app.messaging.command-queue-capacity:100}") int capacity
    ) {
        requirePositive("command-queue-capacity", capacity);
        return QueueBuilder.durable(name)
                .classic()
                .maxLength(capacity)
                .overflow(QueueBuilder.Overflow.rejectPublish)
                .build();
    }

    @Bean
    public AnonymousQueue taskRepliesQueue(
            @Value("${app.messaging.reply-queue-capacity:100}") int capacity
    ) {
        requirePositive("reply-queue-capacity", capacity);
        return new AnonymousQueue(Map.of(
                "x-max-length", capacity,
                "x-overflow", "reject-publish",
                "x-queue-type", "classic"
        ));
    }

    @Bean(destroyMethod = "dispose")
    public Scheduler rabbitRequestScheduler(
            @Value("${app.messaging.request-threads:2}") int threads,
            @Value("${app.messaging.request-queue-capacity:20}") int capacity
    ) {
        requirePositive("request-threads", threads);
        requirePositive("request-queue-capacity", capacity);
        return Schedulers.newBoundedElastic(threads, capacity, "rabbit-request");
    }

    @Bean(destroyMethod = "dispose")
    public Scheduler rabbitReplyScheduler(
            @Value("${app.messaging.reply-threads:2}") int threads,
            @Value("${app.messaging.reply-queue-capacity-per-thread:20}") int capacity
    ) {
        requirePositive("reply-threads", threads);
        requirePositive("reply-queue-capacity-per-thread", capacity);
        return Schedulers.newBoundedElastic(threads, capacity, "rabbit-reply");
    }

    @Bean
    public static BeanPostProcessor separatePublisherConnection() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof RabbitTemplate template) {
                    template.setUsePublisherConnection(true);
                }
                return bean;
            }
        };
    }

    private static void requirePositive(String property, int value) {
        if (value < 1) {
            throw new IllegalArgumentException("app.messaging." + property + " must be positive");
        }
    }

    @Bean
    public JacksonJsonMessageConverter rabbitMessageConverter(
            JsonMapper mapper
    ) {
        return new JacksonJsonMessageConverter(
                mapper,
                "br.edu.unesc.tcc.reactive_task_api.messaging",
                "br.edu.unesc.tcc.reactive_task_api.dto"
        );
    }

    @Bean
    public ContainerCustomizer<SimpleMessageListenerContainer> commandConsumersCustomizer(
            MeterRegistry registry,
            @Value("${app.messaging.command-prefetch:1}") int commandPrefetch,
            @Value("${app.messaging.reply-prefetch:1}") int replyPrefetch
    ) {
        requirePositive("command-prefetch", commandPrefetch);
        requirePositive("reply-prefetch", replyPrefetch);
        return container -> {
            if ("taskReplies".equals(container.getListenerId())) {
                container.setPrefetchCount(replyPrefetch);
                return;
            }
            if (!"taskCommands".equals(container.getListenerId())) {
                return;
            }
            container.setPrefetchCount(commandPrefetch);
            container.setBatchSize(1);
            container.setReceiveTimeout(1000L);
            container.setConsecutiveActiveTrigger(5);
            container.setConsecutiveIdleTrigger(5);
            container.setStartConsumerMinInterval(2000L);
            container.setStopConsumerMinInterval(5000L);

            Gauge.builder("app.messaging.command.consumers", container,
                            SimpleMessageListenerContainer::getActiveConsumerCount)
                    .description("Consumidores ativos de comandos")
                    .register(registry);
        };
    }
}