package com.areascript.taxiapp.notification.domain;

// Lo que las demás features necesitan de las notificaciones: avisarle a un
// usuario. Es el único punto de entrada de la feature para el resto; así
// chat o ride no dependen de cómo se traduce, ni de a quién ni cómo se entrega.
//
// Contrato: NO relanza. Un aviso que falla (usuario sin dispositivo, FCM caído,
// clave sin traducir) se registra y se sigue: una carrera ya aceptada o un
// mensaje ya guardado no se revierten porque la notificación no llegó.
public interface UserNotifier {

    void notifyUser(Recipient recipient, PushMessage message);
}
