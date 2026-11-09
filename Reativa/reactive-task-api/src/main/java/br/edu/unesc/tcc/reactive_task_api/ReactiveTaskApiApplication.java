package br.edu.unesc.tcc.reactive_task_api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ReactiveTaskApiApplication {

	public static void main(String[] args) {
		if (System.getProperty("reactor.netty.ioSelectCount") == null) {
			System.setProperty("reactor.netty.ioSelectCount", "1");
		}
		SpringApplication.run(ReactiveTaskApiApplication.class, args);
	}

}