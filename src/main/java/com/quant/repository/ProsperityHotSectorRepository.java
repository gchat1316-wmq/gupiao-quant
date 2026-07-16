package com.quant.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.quant.entity.ProsperityHotSector;

public interface ProsperityHotSectorRepository extends JpaRepository<ProsperityHotSector, Integer> {

  List<ProsperityHotSector> findBySnapDateOrderByRankNoAsc(LocalDate snapDate);

  Optional<ProsperityHotSector> findBySnapDateAndSectorName(LocalDate snapDate, String sectorName);

  void deleteBySnapDate(LocalDate snapDate);

  Optional<ProsperityHotSector> findFirstByOrderBySnapDateDesc();

  /**
   * 只查必要字段（不含 aiNarrative 这个 TEXT）。 sectors 列表接口使用这个避免拉长文本导致 InnoDB off-page 读。 aiNarrative
   * 另行单查或详情接口取。
   */
  @Query(
      "SELECT new com.quant.service.prosperitystrong.SectorSummaryDTO("
          + "s.id, s.snapDate, s.sectorCode, s.sectorName, s.rankNo, "
          + "s.change1d, s.change5d, s.change20d, s.capitalInflow5d, "
          + "s.upCount, s.downCount, s.leadStock, s.leadStockChange, "
          + "s.persistenceDays, s.score, s.dataSource) "
          + "FROM ProsperityHotSector s WHERE s.snapDate = :snapDate ORDER BY s.rankNo ASC")
  List<com.quant.service.prosperitystrong.SectorSummaryDTO> findSummaryBySnapDate(
      @Param("snapDate") LocalDate snapDate);

  /**
   * 单独批量拉 aiNarrative 字段（带 id 便于组装）。 列表页用上 SectorSummaryDTO 后，仅取这 N 个 id 的 TEXT，避开 Entity 全字段投影。
   */
  @Query(
      value = "SELECT id, ai_narrative FROM prosperity_hot_sector WHERE id IN (:ids)",
      nativeQuery = true)
  List<Object[]> findAiNarrativeByIds(@Param("ids") List<Integer> ids);

  /** 包装：返回 Map<id, aiNarrative> */
  default java.util.Map<Integer, String> findAiNarrativeBatch(List<Integer> ids) {
    if (ids == null || ids.isEmpty()) return java.util.Collections.emptyMap();
    java.util.Map<Integer, String> result = new java.util.HashMap<>();
    for (Object[] row : findAiNarrativeByIds(ids)) {
      if (row[0] != null) {
        result.put((Integer) row[0], (String) row[1]);
      }
    }
    return result;
  }
}
