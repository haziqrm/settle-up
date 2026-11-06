package com.settleup.backend.web;

import com.settleup.backend.group.GroupNotFoundException;
import com.settleup.backend.group.NotGroupMemberException;
import com.settleup.backend.ledger.InvalidExpenseException;
import com.settleup.backend.settlement.SettlementExceptions.NothingToSettleException;
import com.settleup.backend.settlement.SettlementExceptions.RunAlreadyOpenException;
import com.settleup.backend.settlement.SettlementExceptions.RunNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.stream.Collectors;

/**
 * Turns exceptions into RFC 9457 problem+json responses. The base class already covers
 * Spring's own errors (malformed JSON, wrong path-variable type, unknown route) with
 * 400/404 and a generic message. We add our domain exceptions and a catch-all that logs
 * the stack trace server-side but never sends it to the client.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(GroupNotFoundException.class)
    ProblemDetail groupNotFound(GroupNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Group not found", e.getMessage());
    }

    @ExceptionHandler(InvalidExpenseException.class)
    ProblemDetail invalidExpense(InvalidExpenseException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid expense", e.getMessage());
    }

    @ExceptionHandler(NotGroupMemberException.class)
    ProblemDetail notGroupMember(NotGroupMemberException e) {
        return problem(HttpStatus.BAD_REQUEST, "Not a group member", e.getMessage());
    }

    @ExceptionHandler(RunNotFoundException.class)
    ProblemDetail runNotFound(RunNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Settlement run not found", e.getMessage());
    }

    @ExceptionHandler(RunAlreadyOpenException.class)
    ProblemDetail runAlreadyOpen(RunAlreadyOpenException e) {
        return problem(HttpStatus.CONFLICT, "Settlement run already open", e.getMessage());
    }

    @ExceptionHandler(NothingToSettleException.class)
    ProblemDetail nothingToSettle(NothingToSettleException e) {
        return problem(HttpStatus.CONFLICT, "Nothing to settle", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("Unhandled exception", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", "Something went wrong.");
    }

    /** Replace the default "Invalid request content." with the fields that failed. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, "Invalid request", detail));
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setTitle(title);
        return p;
    }
}
