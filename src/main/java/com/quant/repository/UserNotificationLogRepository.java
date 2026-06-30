package com.quant.repository;

import com.quant.entity.UserNotificationLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface UserNotificationLogRepository extends JpaRepository<UserNotificationLog, Long> {

    /** 某用户近 N 天的通知记录，按时间倒序 */
    List<UserNotificationLog> findByUserIdAndSentAtAfterOrderBySentAtDesc(Long userId, LocalDateTime after, Pageable pageable);

    /** 某股票+类型 在某时间之后是否已经发过（用于去重） */
    boolean existsByStockCodeAndTypeAndSentAtAfter(String stockCode, String type, LocalDateTime after);
}
