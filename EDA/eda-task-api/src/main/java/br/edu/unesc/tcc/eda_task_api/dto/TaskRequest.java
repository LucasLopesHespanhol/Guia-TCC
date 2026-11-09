package br.edu.unesc.tcc.eda_task_api.dto;

import br.edu.unesc.tcc.eda_task_api.domain.Priority;
import br.edu.unesc.tcc.eda_task_api.domain.Status;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record TaskRequest(
        @NotBlank(message = "O título é obrigatório")
        @Size(max = 120, message = "O título deve ter até 120 caracteres")
        String titulo,

        @Size(max = 2000, message = "A descrição deve ter até 2000 caracteres")
        String descricao,

        @NotNull(message = "A prioridade é obrigatória")
        Priority prioridade,

        @NotNull(message = "O status é obrigatório")
        Status status,

        @NotNull(message = "A data limite é obrigatória")
        LocalDate dataLimite
) {
}