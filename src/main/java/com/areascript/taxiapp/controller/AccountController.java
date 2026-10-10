package com.areascript.taxiapp.controller;

import com.areascript.taxiapp.security.FirebaseSecurityUtils;
import com.areascript.taxiapp.service.AccountService;
import com.google.firebase.auth.FirebaseToken;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/account")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    // Autoeliminación de cuenta, usada por driver_app y passenger_app desde
    // Settings. El uid sale del token verificado, nunca del cliente -- mismo
    // criterio que el resto de endpoints self-service (ver RideController).
    // 409 si tiene un viaje activo y 500 si falla Firebase: los traduce
    // GlobalExceptionHandler.
    @DeleteMapping
    public ResponseEntity<Void> deleteOwnAccount(HttpServletRequest request) {
        FirebaseToken token = FirebaseSecurityUtils.getToken(request);
        if (token == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        accountService.deleteOwnAccount(token.getUid());
        return ResponseEntity.noContent().build();
    }
}
