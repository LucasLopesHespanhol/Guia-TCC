package br.edu.unesc.tcc.reactive_task_api.config;

import
        io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.reactor.netty.NettyServerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class NettyServerConfig {

    @Bean
    public NettyServerCustomizer acceptBacklogCustomizer(
            @Value("${app.http.accept-backlog:8192}") int backlog
    ) {
        if (backlog < 1) {
            throw new IllegalArgumentException("app.http.accept-backlog must be positive");
        }
        return server -> server.option(ChannelOption.SO_BACKLOG, backlog);
    }
}
