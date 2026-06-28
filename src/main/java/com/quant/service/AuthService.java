package com.quant.service;

import com.quant.entity.AuditLog;
import com.quant.entity.LoginCode;
import com.quant.entity.SmsCode;
import com.quant.entity.User;
import com.quant.repository.AuditLogRepository;
import com.quant.repository.LoginCodeRepository;
import com.quant.repository.SmsCodeRepository;
import com.quant.repository.UserRepository;
import com.quant.security.JwtTokenProvider;
import com.quant.security.UserPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final SmsCodeRepository smsCodeRepository;
    private final LoginCodeRepository loginCodeRepository;
    private final AuditLogRepository auditLogRepository;
    private final SmsService smsService;
    private final JwtTokenProvider tokenProvider;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository,
                       SmsCodeRepository smsCodeRepository,
                       LoginCodeRepository loginCodeRepository,
                       AuditLogRepository auditLogRepository,
                       SmsService smsService,
                       JwtTokenProvider tokenProvider,
                       PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.smsCodeRepository = smsCodeRepository;
        this.loginCodeRepository = loginCodeRepository;
        this.auditLogRepository = auditLogRepository;
        this.smsService = smsService;
        this.tokenProvider = tokenProvider;
        this.passwordEncoder = passwordEncoder;
    }

    // ── 发送验证码 ──────────────────────────────────────

    @Transactional
    public void sendCode(String phone, String ip) {
        // 60秒冷却
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(60);
        SmsCode recent = smsCodeRepository.findValidCode(phone, cutoff);
        if (recent != null) {
            throw new RuntimeException("发送太频繁，请稍后再试");
        }

        String code = smsService.generateCode();
        String sent = smsService.sendCode(phone, code);
        if (sent == null) {
            throw new RuntimeException("短信发送失败，请稍后重试");
        }

        // 标记旧码已用
        smsCodeRepository.markUsed(phone);
        // 保存新码（5分钟有效期）
        SmsCode sms = new SmsCode();
        sms.setPhone(phone);
        sms.setCode(code);
        sms.setExpireAt(LocalDateTime.now().plusMinutes(5));
        smsCodeRepository.save(sms);
    }

    // ── 验证码登录/注册 ──────────────────────────────────

    @Transactional
    public AuthResult verifyCode(String phone, String code, String ip) {
        SmsCode record = smsCodeRepository.findValidCode(phone, LocalDateTime.now());
        if (record == null || !record.getCode().equals(code)) {
            throw new RuntimeException("验证码错误或已过期");
        }
        record.setUsed(true);
        smsCodeRepository.save(record);

        User user = userRepository.findByPhone(phone).orElse(null);
        boolean isNew = false;
        if (user == null) {
            user = new User();
            user.setPhone(phone);
            user.setRole(User.Role.USER);
            user = userRepository.save(user);
            isNew = true;
            log(user.getId(), "USER_REGISTER", "phone:" + phone, Map.of("ip", ip), ip);
        } else {
            user.setLastLoginAt(LocalDateTime.now());
            userRepository.save(user);
            log(user.getId(), "LOGIN_CODE", "phone:" + phone, null, ip);
        }

        String token = tokenProvider.generate(user.getId(), user.getRole().name());
        return new AuthResult(token, isNew, user);
    }

    // ── 密码登录 ────────────────────────────────────────

    @Transactional
    public AuthResult loginWithPassword(String identifier, String password, String ip) {
        // identifier 可以是 phone 或 username
        User user = userRepository.findByPhone(identifier)
                .or(() -> userRepository.findByUsername(identifier))
                .orElse(null);
        if (user == null) {
            throw new RuntimeException("账号不存在");
        }
        if (user.getPasswordHash() == null) {
            throw new RuntimeException("该账号未设置密码，请使用验证码登录或微信扫码");
        }
        if (user.getDisabled()) {
            throw new RuntimeException("账号已被禁用");
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new RuntimeException("密码错误");
        }

        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);
        log(user.getId(), "LOGIN_PASSWORD", identifier, null, ip);

        String token = tokenProvider.generate(user.getId(), user.getRole().name());
        return new AuthResult(token, false, user);
    }

    // ── 设置密码 ────────────────────────────────────────

    @Transactional
    public void setPassword(Long userId, String password) {
        User user = userRepository.findById(userId).orElseThrow(() -> new RuntimeException("用户不存在"));
        user.setPasswordHash(passwordEncoder.encode(password));
        userRepository.save(user);
    }

    // ── 微信登录 ────────────────────────────────────────

    @Transactional
    public AuthResult loginWithWechat(String openid, String unionid, String nickname, String ip) {
        User user = userRepository.findByOpenid(openid).orElse(null);
        boolean isNew = false;
        if (user == null) {
            user = new User();
            user.setOpenid(openid);
            user.setUnionid(unionid);
            user.setUsername(nickname);
            user.setRole(User.Role.USER);
            user = userRepository.save(user);
            isNew = true;
            log(user.getId(), "WECHAT_REGISTER", "openid:" + openid, Map.of("nickname", nickname == null ? "" : nickname), ip);
        } else {
            if (nickname != null && !nickname.isBlank()) {
                user.setUsername(nickname);
            }
            user.setLastLoginAt(LocalDateTime.now());
            userRepository.save(user);
            log(user.getId(), "WECHAT_LOGIN", "openid:" + openid, null, ip);
        }

        String token = tokenProvider.generate(user.getId(), user.getRole().name());
        return new AuthResult(token, isNew, user);
    }

    // ── 审计日志 ────────────────────────────────────────

    private void log(Long userId, String action, String target, Map<String, Object> detail, String ip) {
        AuditLog entry = new AuditLog();
        entry.setUserId(userId);
        entry.setAction(action);
        entry.setTarget(target);
        entry.setDetail(detail);
        entry.setIp(ip);
        auditLogRepository.save(entry);
    }

    // ── 结果封装 ────────────────────────────────────────

    public record AuthResult(String token, boolean isNewUser, User user) {
        public UserDto toDto() {
            return new UserDto(
                user.getId(), user.getPhone(), user.getOpenid(),
                user.getUsername(), user.getRole().name(),
                user.getDisabled(),
                user.getAvatarUrl(),
                user.getNotifyWechat(),
                user.getNotifySms(),
                user.getNotifyPhone()
            );
        }
    }

    public record UserDto(
            Long id,
            String phone,
            String openid,
            String username,
            String role,
            Boolean disabled,
            String avatarUrl,
            Boolean notifyWechat,
            Boolean notifySms,
            Boolean notifyPhone
    ) {}

    // ── 登录码生成（ADMIN 给 MANAGER/ADMIN 发码）────────────

    @Transactional
    public String generateLoginCode(Long issuerId, User.Role intendedRole, int expireDays) {
        if (intendedRole == User.Role.USER) {
            throw new RuntimeException("USER 角色不允许通过登录码注册");
        }
        String code = "GP-" + java.time.LocalDate.now().toString().replace("-", "")
                + "-" + generateRandomCode(6);
        LoginCode lc = new LoginCode();
        lc.setCode(code);
        lc.setIssuerId(issuerId);
        lc.setIntendedRole(intendedRole);
        lc.setExpireAt(LocalDateTime.now().plusDays(expireDays));
        loginCodeRepository.save(lc);
        log(issuerId, "LOGIN_CODE_GENERATED", code, Map.of("intendedRole", intendedRole.name(), "expireDays", expireDays), null);
        return code;
    }

    // ── 登录码登录（用码注册/登录，返回 token）───────────────

    @Transactional
    public AuthResult loginWithCode(String code, String ip) {
        LoginCode lc = loginCodeRepository.findValidCode(code, LocalDateTime.now())
                .orElseThrow(() -> new RuntimeException("登录码无效或已过期"));
        if (lc.getUsed()) {
            throw new RuntimeException("登录码已使用");
        }

        User user = new User();
        user.setRole(lc.getIntendedRole());
        // username 留空，后续用户自行设置
        user = userRepository.save(user);

        lc.setUsed(true);
        lc.setUsedByUserId(user.getId());
        loginCodeRepository.save(lc);

        log(user.getId(), "LOGIN_CODE_USED", code, Map.of("intendedRole", lc.getIntendedRole().name()), ip);
        String token = tokenProvider.generate(user.getId(), user.getRole().name());
        return new AuthResult(token, true, user);
    }

    // ── 用户管理（ADMIN）────────────────────────────────

    @Transactional(readOnly = true)
    public java.util.List<UserDto> listUsers() {
        return userRepository.findAll().stream()
                .map(u -> new UserDto(u.getId(), u.getPhone(), u.getOpenid(),
                        u.getUsername(), u.getRole().name(), u.getDisabled(),
                        u.getAvatarUrl(), u.getNotifyWechat(), u.getNotifySms(), u.getNotifyPhone()))
                .toList();
    }

    @Transactional
    public UserDto updateUserRole(Long adminId, Long targetUserId, User.Role newRole) {
        if (newRole == null) {
            throw new RuntimeException("角色不能为空");
        }
        User target = userRepository.findById(targetUserId)
                .orElseThrow(() -> new RuntimeException("用户不存在: " + targetUserId));
        target.setRole(newRole);
        target = userRepository.save(target);
        log(adminId, "ROLE_CHANGED", "userId:" + targetUserId,
                Map.of("newRole", newRole.name()), null);
        return new UserDto(target.getId(), target.getPhone(), target.getOpenid(),
                target.getUsername(), target.getRole().name(), target.getDisabled(),
                target.getAvatarUrl(), target.getNotifyWechat(), target.getNotifySms(), target.getNotifyPhone());
    }

    @Transactional
    public void toggleUserDisabled(Long adminId, Long targetUserId, boolean disabled) {
        User target = userRepository.findById(targetUserId)
                .orElseThrow(() -> new RuntimeException("用户不存在: " + targetUserId));
        target.setDisabled(disabled);
        userRepository.save(target);
        log(adminId, disabled ? "USER_DISABLED" : "USER_ENABLED",
                "userId:" + targetUserId, null, null);
    }

    /** 更新个人资料（手机号/头像/通知偏好） */
    @Transactional
    public UserDto updateProfile(Long userId, String phone, String avatarUrl,
                                Boolean notifyWechat, Boolean notifySms, Boolean notifyPhone) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        if (phone != null && !phone.isBlank()) {
            if (!phone.equals(user.getPhone()) && userRepository.existsByPhone(phone)) {
                throw new RuntimeException("手机号已被占用");
            }
            user.setPhone(phone);
        }
        if (avatarUrl != null) {
            user.setAvatarUrl(avatarUrl.isBlank() ? null : avatarUrl);
        }
        if (notifyWechat != null) user.setNotifyWechat(notifyWechat);
        if (notifySms != null) user.setNotifySms(notifySms);
        if (notifyPhone != null) user.setNotifyPhone(notifyPhone);
        user = userRepository.save(user);
        return new UserDto(user.getId(), user.getPhone(), user.getOpenid(),
                user.getUsername(), user.getRole().name(), user.getDisabled(),
                user.getAvatarUrl(), user.getNotifyWechat(), user.getNotifySms(), user.getNotifyPhone());
    }

    private String generateRandomCode(int length) {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        java.security.SecureRandom rnd = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(rnd.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
