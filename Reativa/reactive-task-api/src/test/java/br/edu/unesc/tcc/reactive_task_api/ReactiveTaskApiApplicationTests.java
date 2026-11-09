package br.edu.unesc.tcc.reactive_task_api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
		"spring.rabbitmq.listener.simple.auto-startup=false"
})
class ReactiveTaskApiApplicationTests {

	@Test
	void contextLoads() {
	}

}
