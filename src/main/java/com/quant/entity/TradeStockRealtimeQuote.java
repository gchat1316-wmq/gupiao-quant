package com.quant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "trade_stock_realtime_quote")
public class TradeStockRealtimeQuote {

    @Id
    @Column(name = "stock_code", nullable = false, length = 20)
    private String stockCode;

    @Column(name = "quote_time")
    private LocalDateTime quoteTime;

    @Column(name = "latest_price", precision = 12, scale = 4)
    private BigDecimal latestPrice;

    @Column(name = "last_close", precision = 12, scale = 4)
    private BigDecimal lastClose;

    @Column(name = "open_price", precision = 12, scale = 4)
    private BigDecimal openPrice;

    @Column(name = "volume")
    private Long volume;

    @Column(name = "amount", precision = 20, scale = 2)
    private BigDecimal amount;

    @Column(name = "turnover_rate", precision = 10, scale = 4)
    private BigDecimal turnoverRate;

    @Column(name = "kline_time_5m")
    private LocalDateTime klineTime5m;
}
