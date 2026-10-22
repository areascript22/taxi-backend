package com.areascript.taxiapp.dispatch.domain;

// Quién puede tocar la configuración del despacho. Reglas puras: reciben el
// estado y deciden.
//
//   Ver:                 cualquier admin (y el superuser).
//   Editar anillos y     el superuser siempre; un admin solo si el superuser
//   frescura:            encendió "los admins pueden editar".
//   Mover ese            solo el superuser.
//   interruptor:
public final class DispatchConfigPermissions {

    private DispatchConfigPermissions() {
    }

    public static boolean canEdit(boolean callerIsSuperUser, boolean adminsCanEdit) {
        return callerIsSuperUser || adminsCanEdit;
    }

    public static boolean canChangeAdminEditing(boolean callerIsSuperUser) {
        return callerIsSuperUser;
    }
}
