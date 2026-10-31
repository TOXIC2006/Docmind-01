package com.lcdw.springai.docmind01.Controller;

import com.lcdw.springai.docmind01.DTO.SecurityDTO.AuthResponseDTO;
import com.lcdw.springai.docmind01.DTO.SecurityDTO.LoginDTO;
import com.lcdw.springai.docmind01.DTO.SecurityDTO.RegisterDTO;
import com.lcdw.springai.docmind01.Execption.API_Respone;
import com.lcdw.springai.docmind01.Service.Security.AppUserPrincipal;
import com.lcdw.springai.docmind01.Service.Security.Auth;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/docmind/auth")
@RequiredArgsConstructor
@Tag(name = "DocMind Auth API", description = "Register, confirm your email, then sign in for a JWT")
public class AuthController {

    private final Auth authService;

    @PostMapping("/register")
    @Operation(summary = "Create an account",
            description = "Stores a bcrypt hash and emails a verification link. The account cannot sign in until that link is opened.")
    public ResponseEntity<AuthResponseDTO> register(@Valid @RequestBody RegisterDTO registerRequest) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(registerRequest));
    }

    @PostMapping("/login")
    @Operation(summary = "Sign in",
            description = "Returns a JWT to send as 'Authorization: Bearer <token>' on every other endpoint.")
    public ResponseEntity<AuthResponseDTO> login(@Valid @RequestBody LoginDTO loginRequest) {
        return ResponseEntity.ok(authService.login(loginRequest));
    }

    @GetMapping("/verify/email")
    @Operation(summary = "Confirm an email address", description = "This is what the link in the verification email calls.")
    public ResponseEntity<API_Respone> verify(@RequestParam("token_id") String id) {
        String email = authService.verifyEmail(id);
        return ResponseEntity.ok(new API_Respone(true, "Email verified for " + email, 200, LocalDateTime.now()));
    }

    @PostMapping("/resend")
    @Operation(summary = "Send the verification email again")
    public ResponseEntity<Map<String, String>> resend(@RequestBody Map<String, String> request) {
        String email = request.get("email");

        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "email is required"));
        }

        authService.resendVerificationEmail(email);

        return ResponseEntity.ok(Map.of("message",
                "If that address has an unverified DocMind account, a new link is on its way."));
    }

    @GetMapping("/me")
    @Operation(summary = "Who am I",
            description = "Needs a valid token. Lets the frontend restore its session after a page refresh.")
    public ResponseEntity<Map<String, String>> me(@AuthenticationPrincipal AppUserPrincipal principal) {
        return ResponseEntity.ok(Map.of(
                "id", principal.getId(),
                "email", principal.getEmail(),
                "username", principal.getDisplayName(),
                "role", principal.getRole()
        ));
    }
}
