package com.quant.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.quant.entity.UserPool;

@Repository
public interface UserPoolRepository extends JpaRepository<UserPool, Long> {
  List<UserPool> findByUserId(Long userId);

  Optional<UserPool> findByIdAndUserId(Long id, Long userId);
}
