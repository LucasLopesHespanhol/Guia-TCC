package br.edu.unesc.tcc.eda_task_api.domain;

import br.edu.unesc.tcc.eda_task_api.events.TaskEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "task_submissions")
public class TaskSubmission {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private TaskEventType tipo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubmissionStatus estado;

    private Long tarefaId;

    @Lob
    @Column(updatable = false)
    private String dadosJson;

    @Lob
    private String resultadoJson;

    @Column(length = 2000)
    private String erro;

    @Column(nullable = false, updatable = false, secondPrecision = 9)
    private Instant registradaEm;

    @Column(secondPrecision = 9)
    private Instant finalizadaEm;

    protected TaskSubmission() {
    }

    public TaskSubmission(TaskEventType tipo, Long tarefaId, String dadosJson) {
        this.tipo = tipo;
        this.tarefaId = tarefaId;
        this.dadosJson = dadosJson;
        this.estado = SubmissionStatus.REGISTRADA;
        this.registradaEm = Instant.now();
    }

    public UUID getId() { return id; }
    public TaskEventType getTipo() { return tipo; }
    public SubmissionStatus getEstado() { return estado; }
    public Long getTarefaId() { return tarefaId; }
    public String getDadosJson() { return dadosJson; }
    public String getResultadoJson() { return resultadoJson; }
    public String getErro() { return erro; }
    public Instant getRegistradaEm() { return registradaEm; }
    public Instant getFinalizadaEm() { return finalizadaEm; }

    public void complete(Long taskId, String resultJson) {
        finish(SubmissionStatus.CONCLUIDA, null);
        this.tarefaId = taskId;
        this.resultadoJson = resultJson;
    }

    public void reject(String reason) {
        finish(SubmissionStatus.REJEITADA, reason);
    }

    public void fail(String reason) {
        finish(SubmissionStatus.FALHA, reason);
    }

    private void finish(SubmissionStatus status, String reason) {
        if (estado != SubmissionStatus.REGISTRADA) {
            throw new IllegalStateException("Solicitação já finalizada");
        }
        this.estado = status;
        this.erro = reason == null ? null : reason.substring(0, Math.min(reason.length(), 2000));
        this.finalizadaEm = Instant.now();
    }
}
