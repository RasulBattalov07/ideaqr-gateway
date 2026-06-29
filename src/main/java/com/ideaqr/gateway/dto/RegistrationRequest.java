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
    // Audit L-2: restrict to a safe identifier charset — letters, digits, dot, underscore,
    // hyphen. Blocks HTML/script metacharacters and confusable whitespace at the input edge.
    @Pattern(regexp = "^[A-Za-z0-9._-]+$",
            message = "Имя пользователя: только латинские буквы, цифры и символы . _ -")
    private String username;

    // Stronger policy than the old 6-char minimum (audit 4.9). BCrypt caps at 72 bytes.
    @NotBlank(message = "Укажите пароль")
    @Size(min = 12, max = 72, message = "Пароль: не менее 12 символов")
    @Pattern(regexp = "^(?=.*[A-Za-zА-Яа-яЁё])(?=.*\\d).+$",
            message = "Пароль должен содержать буквы и цифры")
    private String password;

    // Audit L-2: Unicode letters (incl. Cyrillic/Kazakh) plus spaces, hyphen, apostrophe and
    // dot — no angle brackets or other HTML/script metacharacters.
    @NotBlank(message = "Укажите имя")
    @Size(max = 80)
    @Pattern(regexp = "^[\\p{L}\\p{M} .'’-]+$", message = "Имя содержит недопустимые символы")
    private String firstName;

    @NotBlank(message = "Укажите фамилию")
    @Size(max = 80)
    @Pattern(regexp = "^[\\p{L}\\p{M} .'’-]+$", message = "Фамилия содержит недопустимые символы")
    private String lastName;

    /** EMPLOYED or UNEMPLOYED. */
    @NotBlank(message = "Укажите статус занятости")
    @Pattern(regexp = "EMPLOYED|UNEMPLOYED", message = "Недопустимый статус занятости")
    private String employmentStatus;

    /**
     * Organization the applicant claims to work for, chosen at sign-up when
     * {@code employmentStatus = EMPLOYED}. Optional and never trusted: it does not grant a
     * role — it raises a membership request that the organization's administrator must
     * approve (see {@code EmploymentService}). Ignored for UNEMPLOYED sign-ups.
     */
    private String organizationUid;

    /** Profession key: DOCTOR, RETAIL_ADMIN, INSPECTOR, CITIZEN. */
    @NotBlank(message = "Укажите профессию")
    private String profession;
}
