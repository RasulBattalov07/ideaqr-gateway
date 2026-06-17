package com.ideaqr.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Registration payload. The {@code profession} field is a key (English) that the
 * server maps to business roles and to the administrator flag; the UI shows a
 * Russian label for each option.
 */
@Data
public class RegistrationRequest {

    @NotBlank(message = "Укажите имя пользователя")
    @Size(min = 3, max = 60, message = "Имя пользователя: от 3 до 60 символов")
    private String username;

    @NotBlank(message = "Укажите пароль")
    @Size(min = 6, max = 72, message = "Пароль: от 6 до 72 символов")
    private String password;

    @NotBlank(message = "Укажите имя")
    @Size(max = 80)
    private String firstName;

    @NotBlank(message = "Укажите фамилию")
    @Size(max = 80)
    private String lastName;

    /** EMPLOYED or UNEMPLOYED. */
    @NotBlank(message = "Укажите статус занятости")
    @Pattern(regexp = "EMPLOYED|UNEMPLOYED", message = "Недопустимый статус занятости")
    private String employmentStatus;

    /** Profession key: DOCTOR, RETAIL_ADMIN, INSPECTOR, CITIZEN. */
    @NotBlank(message = "Укажите профессию")
    private String profession;
}
