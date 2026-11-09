package br.edu.unesc.tcc.reactive_task_api.repository;

import br.edu.unesc.tcc.reactive_task_api.domain.ProcessedCommand;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Repository
public class ProcessedCommandRepository {

    private final DatabaseClient database;

    public ProcessedCommandRepository(DatabaseClient database) {
        this.database = database;
    }

    public Mono<ProcessedCommand> findById(UUID commandId) {
        return database.sql("""
                        SELECT command_id, request_hash, response_json
                        FROM processed_commands
                        WHERE command_id = :commandId
                        """)
                .bind("commandId", commandId)
                .map((row, metadata) -> new ProcessedCommand(
                        row.get("command_id", UUID.class),
                        row.get("request_hash", String.class),
                        row.get("response_json", String.class)
                ))
                .one();
    }

    public Mono<Void> reserve(UUID commandId, String requestHash) {
        return database.sql("""
                        INSERT INTO processed_commands (command_id, request_hash)
                        VALUES (:commandId, :requestHash)
                        """)
                .bind("commandId", commandId)
                .bind("requestHash", requestHash)
                .fetch()
                .rowsUpdated()
                .flatMap(count -> count == 1L
                        ? Mono.<Void>empty()
                        : Mono.error(new IllegalStateException(
                        "A reserva do comando não foi inserida"
                )));
    }

    public Mono<Void> complete(UUID commandId, String responseJson) {
        return database.sql("""
                        UPDATE processed_commands
                        SET response_json = :responseJson
                        WHERE command_id = :commandId
                          AND response_json IS NULL
                        """)
                .bind("commandId", commandId)
                .bind("responseJson", responseJson)
                .fetch()
                .rowsUpdated()
                .flatMap(count -> count == 1L
                        ? Mono.<Void>empty()
                        : Mono.error(new IllegalStateException(
                        "O resultado do comando não foi armazenado"
                )));
    }
}