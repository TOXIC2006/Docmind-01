package com.lcdw.springai.docmind01.DTO.SecurityDTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthResponseDTO {

    private String id;
    private String email;
    private String role;

    /** Bearer token for the Authorization header. Only present on a successful login. */
    private String token;

    /** True once the email address has been confirmed. */
    private Boolean status;

    private Long expiresInMinutes;

    private LocalDate createdAt;
    private LocalDate updatedAt;
}
