package com.settleup.backend.group;

/** No group with this id. Maps to 404. */
public class GroupNotFoundException extends RuntimeException {
    public GroupNotFoundException(long groupId) {
        super("group " + groupId + " not found");
    }
}
