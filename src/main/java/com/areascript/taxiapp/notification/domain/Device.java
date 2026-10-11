package com.areascript.taxiapp.notification.domain;

// Dónde y en qué idioma se le avisa a un usuario. Las dos cosas las guardan
// las apps juntas (junto al perfil) y con el mismo ciclo de vida.
//
// `deviceToken` puede ser null (el usuario nunca registró un dispositivo) y
// `language` puede ser null (documento anterior al selector de idioma).
public record Device(String deviceToken, String language) {
}
