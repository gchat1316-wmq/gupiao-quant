package com.quant.dto.marketrecap;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketRecapBadgeDTO {
    /** 今天(系统当前日期)产生的复盘数 */
    private int today;
    /** 昨天产生的复盘数 */
    private int yesterday;
    /** 最近一篇复盘的 id,用于点击角标跳转,可能为 null */
    private Long latestId;
    /** 最近一篇复盘的 tradeDate (yyyy-MM-dd) */
    private String latestTradeDate;
}
