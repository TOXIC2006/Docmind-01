package com.lcdw.springai.docmind01.Service.Security;

import com.lcdw.springai.docmind01.DTO.SecurityDTO.AuthResponseDTO;
import com.lcdw.springai.docmind01.DTO.SecurityDTO.LoginDTO;
import com.lcdw.springai.docmind01.DTO.SecurityDTO.RegisterDTO;

public interface Auth {

    AuthResponseDTO login(LoginDTO loginDTO);

    AuthResponseDTO register(RegisterDTO registerDTO);

    /** Returns the email address that was just verified. */
    String verifyEmail(String rawToken);

    void resendVerificationEmail(String email);
}
