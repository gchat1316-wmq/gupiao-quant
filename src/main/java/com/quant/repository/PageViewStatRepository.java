package com.quant.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.quant.entity.PageViewStat;

@Repository
public interface PageViewStatRepository extends JpaRepository<PageViewStat, Long> {

  /** 查找用户在指定会话中上一个页面记录（用于计算停留时长） */
  Optional<PageViewStat> findTopBySessionIdAndVisitDateOrderByVisitTimeDesc(
      String sessionId, LocalDate visitDate);

  /** 按日期统计各页面访问量 */
  @Query(
      """
        SELECT p.pagePath, COUNT(p), COUNT(DISTINCT p.userId)
        FROM PageViewStat p
        WHERE p.visitDate = :date
        GROUP BY p.pagePath
        ORDER BY COUNT(p) DESC
        """)
  List<Object[]> countByDateGroupByPage(@Param("date") LocalDate date);

  /** 按日期范围统计 PV/UV */
  @Query(
      """
        SELECT p.visitDate, COUNT(p), COUNT(DISTINCT p.userId)
        FROM PageViewStat p
        WHERE p.visitDate BETWEEN :start AND :end
        GROUP BY p.visitDate
        ORDER BY p.visitDate
        """)
  List<Object[]> countByDateRange(@Param("start") LocalDate start, @Param("end") LocalDate end);

  /** 某页面近 N 天访问量趋势 */
  @Query(
      """
        SELECT p.visitDate, COUNT(p)
        FROM PageViewStat p
        WHERE p.pagePath = :pagePath
          AND p.visitDate BETWEEN :start AND :end
        GROUP BY p.visitDate
        ORDER BY p.visitDate
        """)
  List<Object[]> pageTrend(
      @Param("pagePath") String pagePath,
      @Param("start") LocalDate start,
      @Param("end") LocalDate end);

  /** 某日各页面访问明细 */
  List<PageViewStat> findByVisitDate(LocalDate date);
}
