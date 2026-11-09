package br.edu.unesc.tcc.eda_task_api.repository;

import br.edu.unesc.tcc.eda_task_api.domain.Task;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskRepository extends JpaRepository<Task, Long> {

    List<Task> findAllByOrderByIdAsc();
}