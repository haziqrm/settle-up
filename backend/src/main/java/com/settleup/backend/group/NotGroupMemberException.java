package com.settleup.backend.group;

/** The user is not in the group. Maps to 400 until there is auth, then it becomes 403. */
public class NotGroupMemberException extends RuntimeException {
    public NotGroupMemberException(long groupId, long userId) {
        super("user " + userId + " is not a member of group " + groupId);
    }
}
