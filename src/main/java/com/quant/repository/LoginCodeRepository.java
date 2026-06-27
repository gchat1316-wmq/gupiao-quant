package com.quant.repository;

import com.quant.entity.LoginCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface LoginCodeRepository extends JpaRepository<LoginCode, Long> {

    Optional<LoginCode> findByCode(String code);

    @Query("SELECT c FROM LoginCode c WHERE c.code = :code AND c.used = false AND c.expireAt > :now")
    Optional<LoginCode> findValidCode(@Param("code") String code, @Param("now") LocalDateTime now);

    @Modifying
    @Query("UPDATE LoginCode c SET c.used = true, c.usedByUserId = :userId WHERE c.code = :code")
    int markUsed(@Param("code") String code, @Param("userId") Long userId);
}
