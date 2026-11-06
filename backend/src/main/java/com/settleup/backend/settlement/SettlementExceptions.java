package com.settleup.backend.settlement;

public final class SettlementExceptions {

    private SettlementExceptions() {}

    /** 409: the group already has an OPEN run. runId is null when we lost a race and don't know it. */
    public static class RunAlreadyOpenException extends RuntimeException {
        public RunAlreadyOpenException(long groupId, Long runId) {
            super(runId == null
                    ? "group " + groupId + " already has an open settlement run"
                    : "group " + groupId + " already has an open settlement run: " + runId);
        }
    }

    /** 409: every balance in the group is already zero. */
    public static class NothingToSettleException extends RuntimeException {
        public NothingToSettleException(long groupId) {
            super("group " + groupId + " has nothing to settle");
        }
    }

    /** 404 */
    public static class RunNotFoundException extends RuntimeException {
        public RunNotFoundException(long runId) {
            super("settlement run " + runId + " not found");
        }
    }

    /** 404 */
    public static class PaymentNotFoundException extends RuntimeException {
        public PaymentNotFoundException(long paymentId) {
            super("payment " + paymentId + " not found");
        }
    }

    /** 400 until there is auth, then 403. */
    public static class NotYourPaymentException extends RuntimeException {
        public NotYourPaymentException(long paymentId, long userId) {
            super("user " + userId + " is not the payer of payment " + paymentId);
        }
    }

    /** 409: the run is closed, its deadline has passed, or the payment is already past this point. */
    public static class PaymentNotPayableException extends RuntimeException {
        public PaymentNotPayableException(long paymentId, String reason) {
            super("payment " + paymentId + " cannot be paid: " + reason);
        }
    }

    /** 409: the receiver has no usable linked account, so there is nowhere to send the money. */
    public static class PayeeNotLinkedException extends RuntimeException {
        public PayeeNotLinkedException(long paymentId, long payeeId) {
            super("payment " + paymentId + " cannot be paid yet: the receiver (user " + payeeId
                    + ") has not linked a bank account to receive money");
        }
    }

    /** 409: the state machine does not allow this move from the payment's current status. */
    public static class IllegalPaymentTransitionException extends RuntimeException {
        public IllegalPaymentTransitionException(long paymentId, PaymentStatus from, PaymentStatus to) {
            super("payment " + paymentId + " cannot move from " + from + " to " + to);
        }
    }
}
