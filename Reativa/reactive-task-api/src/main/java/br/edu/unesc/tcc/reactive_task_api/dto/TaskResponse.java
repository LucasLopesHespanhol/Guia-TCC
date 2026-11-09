package br.edu.unesc.tcc.reactive_task_api.dto;

import br.edu.unesc.tcc.reactive_task_api.domain.Priority;
import br.edu.unesc.tcc.reactive_task_api.domain.Status;

import java.time.LocalDate;

public record TaskResponse(
        Long id,
        String titulo,
        String descricao,
        Priority prioridade,
        Status status,
        LocalDate dataLimite
) {
}