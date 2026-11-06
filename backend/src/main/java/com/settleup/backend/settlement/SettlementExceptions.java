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
}
