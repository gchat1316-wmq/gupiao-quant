package com.quant.repository;

import com.quant.entity.UserStockSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserStockSubscriptionRepository
        extends JpaRepository<UserStockSubscription, Long> {

    Optional<UserStockSubscription> findByUserIdAndStockCode(Long userId, String stockCode);

    List<UserStockSubscription> findByUserId(Long userId);

    @Query("SELECT s FROM UserStockSubscription s WHERE s.enabled = true " +
           "AND (s.priceBuy IS NOT NULL OR s.priceStopLoss IS NOT NULL " +
           "OR s.priceAddPosition IS NOT NULL OR s.priceReducePosition IS NOT NULL " +
           "OR s.priceClearPosition IS NOT NULL)")
    List<UserStockSubscription> findAllEnabledWithPrice();

    @Modifying
    @Query("UPDATE UserStockSubscription s SET s.alertBuyTriggeredAt = NULL, " +
           "s.alertStopLossTriggeredAt = NULL, s.alertAddPositionTriggeredAt = NULL, " +
           "s.alertReducePositionTriggeredAt = NULL, s.alertClearPositionTriggeredAt = NULL, " +
           "s.version = s.version + 1 " +
           "WHERE s.userId = :userId AND s.stockCode = :stockCode")
    int clearAllTriggeredAt(@Param("userId") Long userId,
                            @Param("stockCode") String stockCode);
}
