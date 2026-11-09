package br.edu.unesc.tcc.reactive_task_api.exception;


import org.springframework.web.server.ResponseStatusException;
import io.r2dbc.spi.R2dbcNonTransientResourceException;
import io.r2dbc.spi.R2dbcTimeoutException;
import io.r2dbc.spi.R2dbcTransientResourceException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebInputException;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

@RestControllerAdvice
public class GlobalExceptionHandler {


    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> handleCommandFailure(
            ResponseStatusException exception
    ) {
        return problem(
                HttpStatus.valueOf(exception.getStatusCode().value()),
                exception.getReason() == null
                        ? "Falha ao executar a solicitação."
                        : exception.getReason()
        );
    }

    @ExceptionHandler(TaskNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(
            TaskNotFoundException exception
    ) {
        return problem(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<ProblemDetail> handleValidation(
            WebExchangeBindException exception
    ) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Os campos enviados não atendem ao contrato."
        );

        body.setProperty(
                "errors",
                exception.getFieldErrors().stream()
                        .map(error ->
                                error.getField() + ": "
                                        + error.getDefaultMessage()
                        )
                        .sorted()
                        .toList()
        );

        return ResponseEntity.badRequest()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }

    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<ProblemDetail> handleInvalidInput(
            ServerWebInputException exception
    ) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Verifique o JSON, os tipos, os enums e as datas enviados."
        );
    }

    @ExceptionHandler({
            DataAccessResourceFailureException.class,
            TransientDataAccessResourceException.class,
            CannotCreateTransactionException.class,
            R2dbcNonTransientResourceException.class,
            R2dbcTransientResourceException.class,
            R2dbcTimeoutException.class,
            TimeoutException.class,
            RejectedExecutionException.class
    })
    public ResponseEntity<ProblemDetail> handleUnavailable(
            Exception exception
    ) {
        return problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "O serviço está temporariamente indisponível."
        );
    }

    private ResponseEntity<ProblemDetail> problem(
            HttpStatus status,
            String detail
    ) {
        ProblemDetail body =
                ProblemDetail.forStatusAndDetail(status, detail);

        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }
}