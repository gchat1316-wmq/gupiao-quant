package com.quant.repository;

import com.quant.entity.ProsperityPipelineRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProsperityPipelineRunRepository extends JpaRepository<ProsperityPipelineRun, Integer> {

    /** 按快照日期查找最新一条执行记录 */
    Optional<ProsperityPipelineRun> findTopBySnapDateOrderByStartedAtDesc(LocalDate snapDate);

    /** 查找指定日期范围内的执行记录 */
    List<ProsperityPipelineRun> findBySnapDateBetweenOrderByStartedAtDesc(LocalDate from, LocalDate to);

    /** 查找最近一次执行记录 */
    Optional<ProsperityPipelineRun> findTopByOrderByStartedAtDesc();

    /** 删除指定日期的所有执行记录 */
    @Modifying
    @Query("DELETE FROM ProsperityPipelineRun r WHERE r.snapDate = :snapDate")
    int deleteBySnapDate(@Param("snapDate") LocalDate snapDate);
}
