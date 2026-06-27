package com.quant.security;

import com.quant.entity.User;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class UserPrincipal {
    private final Long id;
    private final String phone;
    private final String openid;
    private final String role;
    private final Boolean disabled;

    public static UserPrincipal from(User user) {
        return new UserPrincipal(
            user.getId(),
            user.getPhone(),
            user.getOpenid(),
            user.getRole().name(),
            user.getDisabled()
        );
    }

    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }

    public boolean isManager() {
        return "MANAGER".equals(role);
    }

    public boolean isAdminOrManager() {
        return isAdmin() || isManager();
    }

    public boolean isDisabled() {
        return Boolean.TRUE.equals(disabled);
    }
}
