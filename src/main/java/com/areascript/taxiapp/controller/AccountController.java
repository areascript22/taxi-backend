package com.areascript.taxiapp.controller;

import com.areascript.taxiapp.common.security.AuthenticatedUser;
import com.areascript.taxiapp.service.AccountService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
    public ResponseEntity<Void> deleteOwnAccount(@AuthenticationPrincipal AuthenticatedUser user) {
        accountService.deleteOwnAccount(user.uid());
        return ResponseEntity.noContent().build();
    }
}
