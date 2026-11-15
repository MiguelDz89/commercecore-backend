package com.commercecore.diaz.identity.domain;

import java.util.Objects;
import java.util.Set;

/** Rules for delegation through ordinary tenant roles. */
public final class OrdinaryRolePolicy {
    private static final Set<String> NON_DELEGABLE = Set.of("TENANT_MANAGE", "USER_MANAGE");

    private OrdinaryRolePolicy() {}

    public static void checkAssignment(TenantRole role, Set<String> actorPermissions) {
        Objects.requireNonNull(role, "role");
        checkPermissions(role, role.permissions(), actorPermissions);
    }

    public static void checkPermissionChange(TenantRole role, Set<String> proposedPermissions,
                                             Set<String> actorPermissions, boolean assignedToActor) {
        Objects.requireNonNull(role, "role");
        if (assignedToActor) throw new SecurityException("Cannot modify a role assigned to the actor");
        checkPermissions(role, role.permissions(), actorPermissions);
        checkPermissions(role, proposedPermissions, actorPermissions);
    }

    private static void checkPermissions(TenantRole role, Set<String> permissions, Set<String> actorPermissions) {
        Objects.requireNonNull(permissions, "permissions");
        Objects.requireNonNull(actorPermissions, "actorPermissions");
        if (role.isProtected() || permissions.stream().anyMatch(NON_DELEGABLE::contains)
                || !actorPermissions.containsAll(permissions)) {
            throw new SecurityException("Role delegation denied");
        }
    }
}
