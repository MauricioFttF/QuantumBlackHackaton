package com.seuprojeto.backend.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;

/**
 * Maps exceptions to RFC 7807 responses. Stack traces are logged, never returned.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Invalid input, including a blank message rejected by a record's constructor. */
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ProblemDetail handleBadRequest(Exception e) {
        log.debug("Rejected a malformed request", e);
        return problem(HttpStatus.BAD_REQUEST, "Requisição inválida", rootMessage(e));
    }

    /** A required query parameter was omitted. Without this it would fall to the catch-all as a 500. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail handleMissingParameter(MissingServletRequestParameterException e) {
        log.debug("Missing request parameter", e);
        return problem(HttpStatus.BAD_REQUEST, "Requisição inválida",
                "O parâmetro obrigatório '%s' não foi informado.".formatted(e.getParameterName()));
    }

    /** Unknown route. The catch-all below would otherwise turn a 404 into a 500. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNotFound(NoResourceFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Recurso não encontrado",
                "Nenhum endpoint corresponde a essa rota.");
    }

    /** Gemini failed us — the client did nothing wrong, so this is not a 500. */
    @ExceptionHandler({EmbeddingException.class, GenerationException.class})
    public ProblemDetail handleUpstreamFailure(RuntimeException e) {
        log.error("Upstream AI call failed", e);
        return problem(HttpStatus.BAD_GATEWAY, "Serviço de IA indisponível",
                "A chamada ao provedor de IA falhou. Tente novamente em instantes.");
    }

    /**
     * Reading the ingestion source failed. The path comes from the client, so this is a 400 —
     * and the raw filesystem error is logged rather than returned.
     */
    @ExceptionHandler(IOException.class)
    public ProblemDetail handleIoFailure(IOException e) {
        log.error("I/O failure while handling a request", e);
        return problem(HttpStatus.BAD_REQUEST, "Arquivo inacessível",
                "Não foi possível ler o arquivo informado em 'path'.");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno",
                "Ocorreu um erro inesperado.");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }

    /**
     * A record's compact constructor throws inside Jackson deserialization, so the useful
     * message sits on the cause. Anything else (a genuinely malformed body) gets a generic
     * message rather than Jackson's parser internals.
     */
    private static String rootMessage(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof IllegalArgumentException && cause.getMessage() != null) {
                return cause.getMessage();
            }
        }
        return e instanceof HttpMessageNotReadableException
                ? "Corpo da requisição inválido ou mal formado."
                : String.valueOf(e.getMessage());
    }
}
