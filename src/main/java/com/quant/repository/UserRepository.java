package com.quant.repository;

import com.quant.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByPhone(String phone);
    Optional<User> findByEmail(String email);
    Optional<User> findByUsername(String username);
    Optional<User> findByOpenid(String openid);
    Optional<User> findByUnionid(String unionid);
    boolean existsByPhone(String phone);
    boolean existsByEmail(String email);

    /** 启用了短信通知、未被禁用、且有手机号的用户 */
    @Query("select u from User u where u.disabled = false and u.notifySms = true and u.phone is not null and u.phone <> ''")
    List<User> findActiveSmsTargets();

    /** 启用了指定渠道（true/false）、未被禁用的用户 */
    @Query("select u from User u where u.disabled = false and u.notifyWechat = :wechat")
    List<User> findActiveByWechat(@Param("wechat") boolean wechat);
}
