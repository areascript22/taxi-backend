package com.areascript.taxiapp.user.infra;

import com.areascript.taxiapp.common.error.ExternalServiceException;
import com.areascript.taxiapp.notification.domain.Device;
import com.areascript.taxiapp.notification.domain.DeviceRegistry;
import com.areascript.taxiapp.notification.domain.Recipient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

// Dónde y en qué idioma se le avisa a alguien: el token de notificaciones y el
// idioma viven en el PERFIL de cada app (drivers / passengers), porque son por
// instalación: la misma persona tiene uno en la app de conductor y otro en la de
// pasajero, y el push al pasajero tiene que ir al segundo.
@Component
@ConditionalOnProperty(name = "taxiapp.persistence.postgres", havingValue = "true", matchIfMissing = true)
public class PostgresDeviceRegistry implements DeviceRegistry {

    private static final Logger log = LoggerFactory.getLogger(PostgresDeviceRegistry.class);

    private final UserRowRepository users;
    private final DriverRowRepository drivers;
    private final PassengerRowRepository passengers;

    public PostgresDeviceRegistry(
            UserRowRepository users,
            DriverRowRepository drivers,
            PassengerRowRepository passengers) {
        this.users = users;
        this.drivers = drivers;
        this.passengers = passengers;
    }

    // Vacío si la persona no existe o no tiene perfil en esa app.
    @Override
    @Transactional(readOnly = true)
    public Optional<Device> findDevice(Recipient recipient) {
        try {
            Optional<UserRow> user = users.findByFirebaseUid(recipient.uid());
            if (user.isEmpty()) {
                return Optional.empty();
            }
            if (recipient.kind() == Recipient.Kind.DRIVER) {
                return drivers.findById(user.get().getId())
                        .map(row -> new Device(row.getFcmToken(), row.getLanguage()));
            }
            return passengers.findById(user.get().getId())
                    .map(row -> new Device(row.getFcmToken(), row.getLanguage()));
        } catch (DataAccessException e) {
            log.error("NotificationDebug | Error al buscar el dispositivo de {}/{}: {}",
                    recipient.kind(), recipient.uid(), e.getMessage(), e);
            throw new ExternalServiceException("No se pudo buscar el dispositivo del destinatario", e);
        }
    }
}
