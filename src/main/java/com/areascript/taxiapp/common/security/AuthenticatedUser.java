package com.areascript.taxiapp.common.security;

// Quién hace el request, tal como lo dice el ID token de Firebase ya
// verificado. Es el "principal" de Spring Security: los controllers lo reciben
// con @AuthenticationPrincipal y los services nunca ven el token ni el
// HttpServletRequest.
//
// El acceso a cada endpoint se decide con las authorities (ROLE_ADMIN,
// ROLE_SUPERUSER, ROLE_FOUNDER) y @PreAuthorize. Los booleans admin/superUser
// solo existen para pasarle ese dato a los services de moderación, cuya
// jerarquía compara contra el rol de quien actúa (un admin no actúa sobre otro
// admin, un superuser sí).
public record AuthenticatedUser(
        String uid,
        String email,
        String name,
        String picture,
        boolean admin,
        boolean superUser
) {
}
