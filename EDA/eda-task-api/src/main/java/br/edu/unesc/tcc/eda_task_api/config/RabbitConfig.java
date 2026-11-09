package br.edu.unesc.tcc.eda_task_api.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    public static final String EVENTS = "eda.events.v1";
    public static final String REQUESTS = "eda.requests.v1.q";
    public static final String FACTS = "eda.facts.v1.q";
    public static final String REQUESTS_LISTENER = "taskRequests";
    public static final String FACTS_LISTENER = "taskFacts";

    @Bean
    public Declarables taskEventTopology() {
        var events = new TopicExchange(EVENTS, true, false);
        var failures = new DirectExchange("eda.failures.v1", true, false);
        var requests = QueueBuilder.durable(REQUESTS)
                .deadLetterExchange(failures.getName())
                .deadLetterRoutingKey(REQUESTS + ".dlq").build();
        var facts = QueueBuilder.durable(FACTS)
                .deadLetterExchange(failures.getName())
                .deadLetterRoutingKey(FACTS + ".dlq").build();
        var requestsDlq = QueueBuilder.durable(REQUESTS + ".dlq").build();
        var factsDlq = QueueBuilder.durable(FACTS + ".dlq").build();

        return new Declarables(events, failures, requests, facts, requestsDlq, factsDlq,
                BindingBuilder.bind(requests).to(events).with("submission.*.registered"),
                BindingBuilder.bind(facts).to(events).with("task.*"),
                BindingBuilder.bind(requestsDlq).to(failures).with(REQUESTS + ".dlq"),
                BindingBuilder.bind(factsDlq).to(failures).with(FACTS + ".dlq"));
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

    @Bean
    public MeterBinder requestConsumersMetric(RabbitListenerEndpointRegistry endpoints) {
        return registry -> Gauge.builder("eda.messaging.request.consumers", endpoints,
                        r -> r.getListenerContainer(REQUESTS_LISTENER)
                                instanceof SimpleMessageListenerContainer container
                                ? container.getActiveConsumerCount() : 0)
                .description("Consumidores ativos da fila de registros de solicitacoes")
                .register(registry);
    }
}