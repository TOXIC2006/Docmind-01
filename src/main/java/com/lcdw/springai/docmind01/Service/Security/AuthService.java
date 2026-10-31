package com.lcdw.springai.docmind01.Service.Security;

import com.lcdw.springai.docmind01.DTO.SecurityDTO.AuthResponseDTO;
import com.lcdw.springai.docmind01.DTO.SecurityDTO.LoginDTO;
import com.lcdw.springai.docmind01.DTO.SecurityDTO.RegisterDTO;
import com.lcdw.springai.docmind01.Entity.User;
import com.lcdw.springai.docmind01.Execption.Auth_EXE;
import com.lcdw.springai.docmind01.Repo.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService implements Auth {

    private static final long TOKEN_VALID_HOURS = 24;

    // Never taken from the request: a client-supplied role would let anyone register as ADMIN.
    private static final String DEFAULT_ROLE = "USER";

    private static final String BAD_CREDENTIALS = "Incorrect email or password";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final EmailService emailService;
    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    @Transactional
    public AuthResponseDTO register(RegisterDTO registerDTO) {
        String email = normalizeEmail(registerDTO.getEmail());
        String username = registerDTO.getUsername().trim();

        if (userRepository.existsByEmail(email)) {
            throw new Auth_EXE("An account with this email already exists. Try signing in instead.");
        }
        if (userRepository.existsByUsername(username)) {
            throw new Auth_EXE("That username is already taken.");
        }

        User user = User.builder()
                .username(username)
                .email(email)
                .password(passwordEncoder.encode(registerDTO.getPassword()))
                .role(DEFAULT_ROLE)
                .emailVerified(false)
                .build();

        userRepository.save(user);

        String rawToken = issueVerificationToken(user);

        // Inside the transaction on purpose: if the mail cannot be sent the account is rolled
        // back rather than left behind as a permanently unreachable, unverified row.
        emailService.sendVerificationEmail(user.getEmail(), user.getUsername(), rawToken);

        log.info("registered unverified account {}", user.getId());

        return AuthResponseDTO.builder()
                .id(user.getId())
                .email(user.getEmail())
                .role(user.getRole())
                .status(false)
                .createdAt(toDate(user.getCreatedAt()))
                .build();
    }

    @Override
    public AuthResponseDTO login(LoginDTO loginDTO) {
        String email = normalizeEmail(loginDTO.getEmail());

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(email, loginDTO.getPassword()));
        } catch (AuthenticationException e) {
            throw explainFailure(email, loginDTO.getPassword(), e);
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new Auth_EXE(BAD_CREDENTIALS));

        return AuthResponseDTO.builder()
                .id(user.getId())
                .email(user.getEmail())
                .role(user.getRole())
                .token(jwtService.generateToken(user.getId(), user.getEmail(), user.getRole()))
                .status(true)
                .expiresInMinutes(jwtService.getExpirationMinutes())
                .createdAt(toDate(user.getCreatedAt()))
                .updatedAt(toDate(user.getUpdatedAt()))
                .build();
    }

    @Override
    @Transactional
    public String verifyEmail(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new Auth_EXE("A verification token is required.");
        }

        User user = userRepository.findByVerificationTokenHash(hash(rawToken))
                .orElseThrow(() -> new Auth_EXE("This verification link is invalid. Request a new one."));

        if (user.getVerificationTokenExpiry() == null
                || user.getVerificationTokenExpiry().isBefore(LocalDateTime.now())) {
            throw new Auth_EXE("This verification link has expired. Request a new one.");
        }

        user.setEmailVerified(true);
        user.setVerificationTokenHash(null);
        user.setVerificationTokenExpiry(null);
        userRepository.save(user);

        log.info("verified email for user {}", user.getId());
        return user.getEmail();
    }

    @Override
    @Transactional
    public void resendVerificationEmail(String email) {
        String normalized = normalizeEmail(email);

        User user = userRepository.findByEmail(normalized).orElse(null);

        if (user == null) {
            // Answer identically whether or not the address exists, so this cannot enumerate accounts.
            log.debug("resend requested for an address with no account");
            return;
        }
        if (user.isEmailVerified()) {
            throw new Auth_EXE("That email is already verified. You can sign in.");
        }

        String rawToken = issueVerificationToken(user);
        emailService.sendVerificationEmail(user.getEmail(), user.getUsername(), rawToken);
    }


    private Auth_EXE explainFailure(String email, String suppliedPassword, AuthenticationException cause) {
        User user = userRepository.findByEmail(email).orElse(null);

        if (user != null
                && !user.isEmailVerified()
                && passwordEncoder.matches(suppliedPassword, user.getPassword())) {
            return new Auth_EXE("Your email is not verified yet. Open the link we sent you, or request a new one.");
        }

        log.debug("login rejected for {}: {}", email, cause.getMessage());
        return new Auth_EXE(BAD_CREDENTIALS);
    }

    private String issueVerificationToken(User user) {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        user.setVerificationTokenHash(hash(rawToken));
        user.setVerificationTokenExpiry(LocalDateTime.now().plusHours(TOKEN_VALID_HOURS));
        userRepository.save(user);

        return rawToken;
    }

    private static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available on this JVM", e);
        }
    }

    private static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }

    private static LocalDate toDate(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.toLocalDate();
    }
}
