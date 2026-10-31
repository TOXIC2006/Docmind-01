package com.lcdw.springai.docmind01.Service.Security;

import com.lcdw.springai.docmind01.Execption.Auth_EXE;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentUser {

    private CurrentUser() {
    }

    public static AppUserPrincipal get() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null
                || !(authentication.getPrincipal() instanceof AppUserPrincipal principal)) {
            throw new Auth_EXE("Authentication required. Sign in to continue.");
        }

        return principal;
    }
}
