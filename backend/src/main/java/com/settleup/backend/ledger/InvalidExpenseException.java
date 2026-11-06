package com.settleup.backend.ledger;

/** The expense request is malformed or breaks a business rule. Maps to 400. */
public class InvalidExpenseException extends RuntimeException {
    public InvalidExpenseException(String message) {
        super(message);
    }
}
