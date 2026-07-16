package com.quant.service;

import java.time.LocalDateTime;
import java.util.Map;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quant.entity.AuditLog;
import com.quant.entity.EmailCode;
import com.quant.entity.LoginCode;
import com.quant.entity.SmsCode;
import com.quant.entity.User;
import com.quant.repository.AuditLogRepository;
import com.quant.repository.EmailCodeRepository;
import com.quant.repository.LoginCodeRepository;
import com.quant.repository.SmsCodeRepository;
import com.quant.repository.UserRepository;
import com.quant.security.JwtTokenProvider;

@Service
public class AuthService {

  private final UserRepository userRepository;
  private final SmsCodeRepository smsCodeRepository;
  private final EmailCodeRepository emailCodeRepository;
  private final LoginCodeRepository loginCodeRepository;
  private final AuditLogRepository auditLogRepository;
  private final SmsService smsService;
  private final EmailService emailService;
  private final JwtTokenProvider tokenProvider;
  private final PasswordEncoder passwordEncoder;

  public AuthService(
      UserRepository userRepository,
      SmsCodeRepository smsCodeRepository,
      EmailCodeRepository emailCodeRepository,
      LoginCodeRepository loginCodeRepository,
      AuditLogRepository auditLogRepository,
      SmsService smsService,
      EmailService emailService,
      JwtTokenProvider tokenProvider,
      PasswordEncoder passwordEncoder) {
    this.userRepository = userRepository;
    this.smsCodeRepository = smsCodeRepository;
    this.emailCodeRepository = emailCodeRepository;
    this.loginCodeRepository = loginCodeRepository;
    this.auditLogRepository = auditLogRepository;
    this.smsService = smsService;
    this.emailService = emailService;
    this.tokenProvider = tokenProvider;
    this.passwordEncoder = passwordEncoder;
  }

  // ── 发送短信验证码 ──────────────────────────────────

  /**
   * 发送短信验证码。
   *
   * @return 已生成并入库的验证码 code（仅 mock 模式由 controller 回传给前端自动回填）
   */
  @Transactional
  public String sendCode(String phone, String ip) {
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
    return code;
  }

  // ── 短信验证码登录/注册 ──────────────────────────────

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

  // ── 密码登录（identifier 支持 手机号/邮箱/用户名） ─────────────────

  @Transactional
  public AuthResult loginWithPassword(String identifier, String password, String ip) {
    // identifier 可以是 phone / email / username
    User user =
        userRepository
            .findByPhone(identifier)
            .or(() -> userRepository.findByEmail(identifier))
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

  // ── 手机号 + 密码 注册 ───────────────────────────────

  @Transactional
  public AuthResult registerWithPhone(String phone, String password, String ip) {
    if (phone == null || phone.isBlank()) {
      throw new RuntimeException("手机号不能为空");
    }
    if (userRepository.existsByPhone(phone)) {
      throw new RuntimeException("该手机号已注册");
    }
    User user = new User();
    user.setPhone(phone);
    user.setPasswordHash(passwordEncoder.encode(password));
    user.setRole(User.Role.USER);
    user = userRepository.save(user);
    log(user.getId(), "PHONE_REGISTER", "phone:" + phone, Map.of("ip", ip), ip);
    String token = tokenProvider.generate(user.getId(), user.getRole().name());
    return new AuthResult(token, true, user);
  }

  // ── 邮箱 + 密码 注册 ───────────────────────────────

  @Transactional
  public AuthResult registerWithEmail(String email, String password, String ip) {
    if (email == null || email.isBlank()) {
      throw new RuntimeException("邮箱不能为空");
    }
    if (userRepository.existsByEmail(email)) {
      throw new RuntimeException("该邮箱已注册");
    }
    User user = new User();
    user.setEmail(email);
    user.setPasswordHash(passwordEncoder.encode(password));
    user.setRole(User.Role.USER);
    user = userRepository.save(user);
    log(user.getId(), "EMAIL_REGISTER", "email:" + email, Map.of("ip", ip), ip);
    String token = tokenProvider.generate(user.getId(), user.getRole().name());
    return new AuthResult(token, true, user);
  }

  // ── 发送邮箱验证码 ───────────────────────────────

  /**
   * 发送邮箱验证码。
   *
   * @return 已生成并入库的验证码 code（仅 mock 模式由 controller 回传给前端自动回填）
   */
  @Transactional
  public String sendEmailCode(String email, String ip) {
    if (email == null || email.isBlank()) {
      throw new RuntimeException("邮箱不能为空");
    }
    // 60 秒冷却：只要还有未过期未使用的码，就视为频繁
    EmailCode recent =
        emailCodeRepository.findValidCode(email, LocalDateTime.now().minusSeconds(60));
    if (recent != null) {
      throw new RuntimeException("发送太频繁，请稍后再试");
    }

    String code = emailService.generateCode();
    String sent = emailService.sendCode(email, code);
    if (sent == null) {
      throw new RuntimeException("邮件发送失败，请稍后重试");
    }

    // 标记旧码已用
    emailCodeRepository.markUsed(email);
    // 保存新码（5 分钟有效期）
    EmailCode record = new EmailCode();
    record.setEmail(email);
    record.setCode(code);
    record.setExpireAt(LocalDateTime.now().plusMinutes(5));
    emailCodeRepository.save(record);
    return code;
  }

  // ── 邮箱验证码登录/注册 ───────────────────────────────

  @Transactional
  public AuthResult verifyEmailCode(String email, String code, String ip) {
    EmailCode record = emailCodeRepository.findValidCode(email, LocalDateTime.now());
    if (record == null || !record.getCode().equals(code)) {
      throw new RuntimeException("验证码错误或已过期");
    }
    record.setUsed(true);
    emailCodeRepository.save(record);

    User user = userRepository.findByEmail(email).orElse(null);
    boolean isNew = false;
    if (user == null) {
      user = new User();
      user.setEmail(email);
      user.setRole(User.Role.USER);
      user = userRepository.save(user);
      isNew = true;
      log(user.getId(), "EMAIL_CODE_REGISTER", "email:" + email, Map.of("ip", ip), ip);
    } else {
      user.setLastLoginAt(LocalDateTime.now());
      userRepository.save(user);
      log(user.getId(), "EMAIL_CODE_LOGIN", "email:" + email, null, ip);
    }

    String token = tokenProvider.generate(user.getId(), user.getRole().name());
    return new AuthResult(token, isNew, user);
  }

  // ── 短信验证码重置密码 ───────────────────────────────

  @Transactional
  public void resetPasswordBySms(String phone, String code, String newPassword, String ip) {
    SmsCode record = smsCodeRepository.findValidCode(phone, LocalDateTime.now());
    if (record == null || !record.getCode().equals(code)) {
      throw new RuntimeException("验证码错误或已过期");
    }
    User user = userRepository.findByPhone(phone).orElseThrow(() -> new RuntimeException("用户不存在"));
    user.setPasswordHash(passwordEncoder.encode(newPassword));
    userRepository.save(user);

    record.setUsed(true);
    smsCodeRepository.save(record);

    log(user.getId(), "RESET_PASSWORD_SMS", "phone:" + phone, null, ip);
  }

  // ── 邮箱验证码重置密码 ───────────────────────────────

  @Transactional
  public void resetPasswordByEmail(String email, String code, String newPassword, String ip) {
    EmailCode record = emailCodeRepository.findValidCode(email, LocalDateTime.now());
    if (record == null || !record.getCode().equals(code)) {
      throw new RuntimeException("验证码错误或已过期");
    }
    User user = userRepository.findByEmail(email).orElseThrow(() -> new RuntimeException("用户不存在"));
    user.setPasswordHash(passwordEncoder.encode(newPassword));
    userRepository.save(user);

    record.setUsed(true);
    emailCodeRepository.save(record);

    log(user.getId(), "RESET_PASSWORD_EMAIL", "email:" + email, null, ip);
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
      log(
          user.getId(),
          "WECHAT_REGISTER",
          "openid:" + openid,
          Map.of("nickname", nickname == null ? "" : nickname),
          ip);
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

  private void log(
      Long userId, String action, String target, Map<String, Object> detail, String ip) {
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
          user.getId(),
          user.getPhone(),
          user.getEmail(),
          user.getOpenid(),
          user.getUsername(),
          user.getRole().name(),
          user.getDisabled(),
          user.getAvatarUrl(),
          user.getNotifyWechat(),
          user.getNotifySms(),
          user.getNotifyPhone());
    }
  }

  public record UserDto(
      Long id,
      String phone,
      String email,
      String openid,
      String username,
      String role,
      Boolean disabled,
      String avatarUrl,
      Boolean notifyWechat,
      Boolean notifySms,
      Boolean notifyPhone) {}

  // ── 登录码生成（ADMIN 给 MANAGER/ADMIN 发码）────────────

  @Transactional
  public String generateLoginCode(Long issuerId, User.Role intendedRole, int expireDays) {
    if (intendedRole == User.Role.USER) {
      throw new RuntimeException("USER 角色不允许通过登录码注册");
    }
    String code =
        "GP-" + java.time.LocalDate.now().toString().replace("-", "") + "-" + generateRandomCode(6);
    LoginCode lc = new LoginCode();
    lc.setCode(code);
    lc.setIssuerId(issuerId);
    lc.setIntendedRole(intendedRole);
    lc.setExpireAt(LocalDateTime.now().plusDays(expireDays));
    loginCodeRepository.save(lc);
    log(
        issuerId,
        "LOGIN_CODE_GENERATED",
        code,
        Map.of("intendedRole", intendedRole.name(), "expireDays", expireDays),
        null);
    return code;
  }

  // ── 登录码登录（用码注册/登录，返回 token）───────────────

  @Transactional
  public AuthResult loginWithCode(String code, String ip) {
    LoginCode lc =
        loginCodeRepository
            .findValidCode(code, LocalDateTime.now())
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

    log(
        user.getId(),
        "LOGIN_CODE_USED",
        code,
        Map.of("intendedRole", lc.getIntendedRole().name()),
        ip);
    String token = tokenProvider.generate(user.getId(), user.getRole().name());
    return new AuthResult(token, true, user);
  }

  // ── 用户管理（ADMIN）────────────────────────────────

  @Transactional(readOnly = true)
  public java.util.List<UserDto> listUsers() {
    return userRepository.findAll().stream()
        .map(
            u ->
                new UserDto(
                    u.getId(),
                    u.getPhone(),
                    u.getEmail(),
                    u.getOpenid(),
                    u.getUsername(),
                    u.getRole().name(),
                    u.getDisabled(),
                    u.getAvatarUrl(),
                    u.getNotifyWechat(),
                    u.getNotifySms(),
                    u.getNotifyPhone()))
        .toList();
  }

  @Transactional
  public UserDto updateUserRole(Long adminId, Long targetUserId, User.Role newRole) {
    if (newRole == null) {
      throw new RuntimeException("角色不能为空");
    }
    if (adminId.equals(targetUserId)) {
      throw new RuntimeException("不能修改自己的角色");
    }
    User target =
        userRepository
            .findById(targetUserId)
            .orElseThrow(() -> new RuntimeException("用户不存在: " + targetUserId));
    target.setRole(newRole);
    target = userRepository.save(target);
    log(adminId, "ROLE_CHANGED", "userId:" + targetUserId, Map.of("newRole", newRole.name()), null);
    return new UserDto(
        target.getId(),
        target.getPhone(),
        target.getEmail(),
        target.getOpenid(),
        target.getUsername(),
        target.getRole().name(),
        target.getDisabled(),
        target.getAvatarUrl(),
        target.getNotifyWechat(),
        target.getNotifySms(),
        target.getNotifyPhone());
  }

  @Transactional
  public void toggleUserDisabled(Long adminId, Long targetUserId, boolean disabled) {
    if (adminId.equals(targetUserId)) {
      throw new RuntimeException("不能禁用自己的账号");
    }
    User target =
        userRepository
            .findById(targetUserId)
            .orElseThrow(() -> new RuntimeException("用户不存在: " + targetUserId));
    target.setDisabled(disabled);
    userRepository.save(target);
    log(adminId, disabled ? "USER_DISABLED" : "USER_ENABLED", "userId:" + targetUserId, null, null);
  }

  /** 更新个人资料（手机号/头像/通知偏好） */
  @Transactional
  public UserDto updateProfile(
      Long userId,
      String phone,
      String phoneCode,
      String avatarUrl,
      Boolean notifyWechat,
      Boolean notifySms,
      Boolean notifyPhone,
      String ip) {
    User user = userRepository.findById(userId).orElseThrow(() -> new RuntimeException("用户不存在"));
    if (phone != null && !phone.isBlank()) {
      // 与原手机号不同 → 视为改号，必须通过该新手机号下的短信验证码
      if (!phone.equals(user.getPhone())) {
        if (phoneCode == null || phoneCode.isBlank()) {
          throw new RuntimeException("修改手机号需要先填写短信验证码");
        }
        SmsCode record = smsCodeRepository.findValidCode(phone, LocalDateTime.now());
        if (record == null || !record.getCode().equals(phoneCode)) {
          throw new RuntimeException("短信验证码错误或已过期");
        }
        record.setUsed(true);
        smsCodeRepository.save(record);
        if (userRepository.existsByPhone(phone)) {
          throw new RuntimeException("手机号已被占用");
        }
        log(userId, "PHONE_CHANGED", "phone:" + phone, null, ip);
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
    return new UserDto(
        user.getId(),
        user.getPhone(),
        user.getEmail(),
        user.getOpenid(),
        user.getUsername(),
        user.getRole().name(),
        user.getDisabled(),
        user.getAvatarUrl(),
        user.getNotifyWechat(),
        user.getNotifySms(),
        user.getNotifyPhone());
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
