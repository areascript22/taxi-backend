package com.areascript.taxiapp.dispatch.domain;

// Si un conductor está en línea y si está en una carrera.
public record PresenceState(boolean online, boolean onRide) {
}
