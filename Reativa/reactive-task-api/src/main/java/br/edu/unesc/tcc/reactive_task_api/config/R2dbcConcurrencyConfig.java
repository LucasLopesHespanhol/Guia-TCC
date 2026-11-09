package br.edu.unesc.tcc.reactive_task_api.config;

import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.connection.DelegatingConnectionFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Configuration(proxyBeanMethods = false)
public class R2dbcConcurrencyConfig {

    @Bean
    public static BeanPostProcessor offloadPooledConnections() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof ConnectionPool pool) {
                    return new OffloadingConnectionFactory(pool);
                }
                return bean;
            }
        };
    }

    static final class OffloadingConnectionFactory
            extends DelegatingConnectionFactory implements Disposable {

        private final ConnectionPool pool;

        OffloadingConnectionFactory(ConnectionPool pool) {
            super(pool);
            this.pool = pool;
        }

        @Override
        public Mono<? extends Connection> create() {
            return Mono.from(pool.create())
                    .publishOn(Schedulers.boundedElastic());
        }

        @Override
        public void dispose() {
            pool.dispose();
        }

        @Override
        public boolean isDisposed() {
            return pool.isDisposed();
        }
    }
}
