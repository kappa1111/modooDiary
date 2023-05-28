package com.secondWind.modooDiary.api.member.auth.domain.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class PasswordUpdateRequest {
    private String currentPassword;
    private String newPassword;
}
