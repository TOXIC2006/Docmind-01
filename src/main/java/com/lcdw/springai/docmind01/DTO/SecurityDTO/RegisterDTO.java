package com.lcdw.springai.docmind01.DTO.SecurityDTO;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RegisterDTO {
     @NotNull
     private String username;
     @Email
     private String email;
     @Size(min = 8, message = "Password must be at least 8 characters long")
     @NotNull(message = "Password is required")
     private String password;
     private String  role;

}
