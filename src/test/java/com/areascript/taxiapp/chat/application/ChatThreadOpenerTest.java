package com.areascript.taxiapp.chat.application;

import com.areascript.taxiapp.chat.domain.ChatMessage;
import com.areascript.taxiapp.chat.domain.ChatRepository;
import com.areascript.taxiapp.common.error.ExternalServiceException;
import com.areascript.taxiapp.ride.domain.RideAccepted;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ChatThreadOpenerTest {

    private final List<String> threads = new ArrayList<>();
    private RuntimeException failure;

    private final ChatRepository repository = new ChatRepository() {
        @Override
        public void createThread(String rideId, String passengerId, String driverId) {
            if (failure != null) {
                throw failure;
            }
            threads.add(rideId + ":" + passengerId + ":" + driverId);
        }

        @Override
        public void addMessage(ChatMessage message) {
            throw new UnsupportedOperationException();
        }
    };

    @Test
    void al_aceptarse_la_carrera_abre_el_hilo_con_sus_dos_participantes() {
        new ChatThreadOpener(repository).on(new RideAccepted("ride-1", "p1", "d1"));

        assertThat(threads).containsExactly("ride-1:p1:d1");
    }

    // Una carrera ya aceptada no se revierte porque falle el chat.
    @Test
    void un_fallo_al_abrir_el_hilo_no_se_propaga() {
        failure = new ExternalServiceException("firestore caído", new RuntimeException());

        assertThatCode(() -> new ChatThreadOpener(repository).on(new RideAccepted("ride-1", "p1", "d1")))
                .doesNotThrowAnyException();
        assertThat(threads).isEmpty();
    }
}
