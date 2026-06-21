package com.ideaqr.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Payload for a user-initiated password change. The new password is held to the
 * same policy as registration (audit 4.9): at least 12 characters, with letters
 * and digits.
 */
@Data
public class ChangePasswordRequest {

    @NotBlank(message = "Укажите текущий пароль")
    private String currentPassword;

    @NotBlank(message = "Укажите новый пароль")
    @Size(min = 12, max = 72, message = "Пароль: не менее 12 символов")
    @Pattern(regexp = "^(?=.*[A-Za-zА-Яа-яЁё])(?=.*\\d).+$",
            message = "Пароль должен содержать буквы и цифры")
    private String newPassword;
}
