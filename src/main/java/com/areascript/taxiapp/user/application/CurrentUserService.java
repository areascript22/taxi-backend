package com.areascript.taxiapp.user.application;

import com.areascript.taxiapp.user.domain.MyProfile;
import com.areascript.taxiapp.user.domain.UserAccount;
import com.areascript.taxiapp.user.domain.UserAccounts;
import com.areascript.taxiapp.user.domain.UserProfiles;
import org.springframework.stereotype.Service;

// Quién es la persona que hace el request y qué perfiles tiene.
@Service
public class CurrentUserService {

    private final UserAccounts accounts;
    private final UserProfiles profiles;

    public CurrentUserService(UserAccounts accounts, UserProfiles profiles) {
        this.accounts = accounts;
        this.profiles = profiles;
    }

    // Si es su primer request, la persona se crea en este momento (con rol USER).
    public MyProfile me(String firebaseUid, String email, String displayName) {
        UserAccount account = accounts.findOrProvision(firebaseUid, email, displayName);
        return new MyProfile(
                account,
                profiles.findDriver(account.id()).orElse(null),
                profiles.findPassenger(account.id()).orElse(null));
    }
}
