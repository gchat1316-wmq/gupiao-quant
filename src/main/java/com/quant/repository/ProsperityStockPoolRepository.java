package com.quant.repository;

import com.quant.entity.ProsperityStockPool;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProsperityStockPoolRepository extends JpaRepository<ProsperityStockPool, Integer> {

    /** 按 owner 查个人池（NULL owner_id 为系统共享，兼容历史） */
    List<ProsperityStockPool> findByOwnerIdOrderByLastAddedAtDesc(Long ownerId);

    /** 按 owner 查指定股票 */
    Optional<ProsperityStockPool> findByOwnerIdAndStockCode(Long ownerId, String stockCode);

    /** 系统共享池（owner_id 为 NULL） */
    List<ProsperityStockPool> findByOwnerIdIsNullOrderByLastAddedAtDesc();

    Optional<ProsperityStockPool> findByOwnerIdIsNullAndStockCode(String stockCode);

    /** 迁移用：全局按 code 查（只返回第一条） */
    Optional<ProsperityStockPool> findByStockCode(String stockCode);

    List<ProsperityStockPool> findAllByOrderByLastAddedAtDesc();

    /** 测试 / 手动清理用 */
    long deleteByStockCode(String stockCode);
}
