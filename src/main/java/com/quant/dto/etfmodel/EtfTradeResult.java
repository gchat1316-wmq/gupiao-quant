package com.quant.dto.etfmodel;

import java.util.List;

import com.quant.entity.EtfTrade;

/** 录单结果：交易已保存 + 纪律校验警告（不阻断保存，由前端醒目提示）。 */
public record EtfTradeResult(EtfTrade trade, List<String> warnings) {}
