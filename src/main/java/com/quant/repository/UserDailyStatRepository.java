package com.quant.repository;

import com.quant.entity.UserDailyStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserDailyStatRepository extends JpaRepository<UserDailyStat, Long> {

    Optional<UserDailyStat> findByUserIdAndStatDate(Long userId, LocalDate statDate);

    List<UserDailyStat> findByStatDateOrderByPageViewCountDesc(LocalDate statDate);

    /** 每日总体概况（包含游客） */
    @Query("""
        SELECT u.statDate,
               COUNT(u),
               SUM(u.pageViewCount),
               SUM(u.totalDurationSeconds),
               SUM(u.loginCount),
               SUM(CASE WHEN u.isNewUser = true THEN 1 ELSE 0 END)
        FROM UserDailyStat u
        WHERE u.statDate BETWEEN :start AND :end
        GROUP BY u.statDate
        ORDER BY u.statDate
        """)
    List<Object[]> dailyOverview(@Param("start") LocalDate start, @Param("end") LocalDate end);

    /** 某日 top N 用户（按页面访问量） */
    @Query("""
        SELECT u FROM UserDailyStat u
        WHERE u.statDate = :date AND u.userId IS NOT NULL
        ORDER BY u.pageViewCount DESC
        """)
    List<UserDailyStat> topUsersByDate(@Param("date") LocalDate date);

    /** 某日 top N 页面（按访问量） */
    @Query("""
        SELECT p.pagePath, COUNT(p), COUNT(DISTINCT p.userId)
        FROM PageViewStat p
        WHERE p.visitDate = :date
        GROUP BY p.pagePath
        ORDER BY COUNT(p) DESC
        """)
    List<Object[]> topPagesByDate(@Param("date") LocalDate date);

    /** 某用户近 N 天访问天数 */
    @Query("""
        SELECT COUNT(DISTINCT u.statDate) FROM UserDailyStat u
        WHERE u.userId = :userId AND u.statDate >= :since
        """)
    Long countActiveDays(@Param("userId") Long userId, @Param("since") LocalDate since);
}
