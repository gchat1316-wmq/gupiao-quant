package com.quant.repository;

import com.quant.entity.SmsCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface SmsCodeRepository extends JpaRepository<SmsCode, Long> {

    @Query("SELECT s FROM SmsCode s WHERE s.phone = :phone AND s.used = false AND s.expireAt > :now ORDER BY s.createdAt DESC LIMIT 1")
    SmsCode findValidCode(@Param("phone") String phone, @Param("now") LocalDateTime now);

    @Modifying
    @Query("UPDATE SmsCode s SET s.used = true WHERE s.phone = :phone AND s.used = false")
    void markUsed(@Param("phone") String phone);
}
