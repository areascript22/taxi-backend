package com.areascript.taxiapp.identity.domain;

// Los privilegios de una cuenta, tal como están asignados en el proveedor de
// identidad (custom claims de Firebase Auth). Un superuser también tiene
// admin=true; el rol "driver" no es un privilegio y no aparece acá.
public record AccountRoles(boolean admin, boolean superUser) {

    public static final AccountRoles NONE = new AccountRoles(false, false);
}
