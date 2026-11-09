package br.edu.unesc.tcc.reactive_task_api.repository;

import br.edu.unesc.tcc.reactive_task_api.domain.Task;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface TaskRepository extends ReactiveCrudRepository<Task, Long> {

    Flux<Task> findAllByOrderByIdAsc();
}
