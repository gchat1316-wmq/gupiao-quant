package com.quant.dto.stats;

import lombok.Data;
import java.time.LocalDate;

/** 每日概况 DTO */
@Data
public class DailyOverviewDTO {
    private LocalDate date;
    private int userCount;          // 有记录的用户数
    private int pageViewCount;      // 当日总 PV
    private int totalDurationSec;   // 当日总使用时长（秒）
    private int loginCount;         // 当日登录次数
    private int newUserCount;       // 当日新注册用户数
    private int uniqueVisitors;     // 当日独立访客（去重 userId）
}
