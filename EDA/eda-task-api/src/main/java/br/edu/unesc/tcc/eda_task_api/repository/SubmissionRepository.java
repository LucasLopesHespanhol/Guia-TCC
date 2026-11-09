package br.edu.unesc.tcc.eda_task_api.repository;

import br.edu.unesc.tcc.eda_task_api.domain.SubmissionStatus;
import br.edu.unesc.tcc.eda_task_api.domain.TaskSubmission;
import br.edu.unesc.tcc.eda_task_api.events.TaskEventType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SubmissionRepository extends JpaRepository<TaskSubmission, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from TaskSubmission s where s.id = :id")
    Optional<TaskSubmission> findForProcessing(@Param("id") UUID id);

    @Query("select new br.edu.unesc.tcc.eda_task_api.repository.SubmissionRepository$StatusView("
            + "s.id, s.tipo, s.estado, s.tarefaId, s.resultadoJson, s.erro, s.registradaEm, s.finalizadaEm) "
            + "from TaskSubmission s where s.id = :id")
    Optional<StatusView> findStatusView(@Param("id") UUID id);

    record StatusView(UUID id, TaskEventType tipo, SubmissionStatus estado, Long tarefaId,
                      String resultadoJson, String erro, Instant registradaEm, Instant finalizadaEm) {
    }
}
