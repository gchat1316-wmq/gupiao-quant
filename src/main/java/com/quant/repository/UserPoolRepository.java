package com.quant.repository;

import com.quant.entity.UserPool;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserPoolRepository extends JpaRepository<UserPool, Long> {
    List<UserPool> findByUserId(Long userId);
    Optional<UserPool> findByIdAndUserId(Long id, Long userId);
}
