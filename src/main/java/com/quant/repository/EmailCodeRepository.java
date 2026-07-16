package com.quant.repository;

import java.time.LocalDateTime;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.quant.entity.EmailCode;

@Repository
public interface EmailCodeRepository extends JpaRepository<EmailCode, Long> {

  @Query(
      "SELECT e FROM EmailCode e WHERE e.email = :email AND e.used = false AND e.expireAt > :now ORDER BY e.createdAt DESC LIMIT 1")
  EmailCode findValidCode(@Param("email") String email, @Param("now") LocalDateTime now);

  @Modifying
  @Query("UPDATE EmailCode e SET e.used = true WHERE e.email = :email AND e.used = false")
  void markUsed(@Param("email") String email);
}
