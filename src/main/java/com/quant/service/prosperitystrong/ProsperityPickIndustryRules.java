package com.quant.service.prosperitystrong;

import org.springframework.stereotype.Component;

/**
 * 紫苏叶产业链定位 inference rules (ported from {@code StockAnalysisService}). Pure functions over industry
 * keyword + stock-name keyword patterns; no Spring state, kept as a Spring bean for consistency and
 * future DI of an extended rules table.
 */
@Component
public class ProsperityPickIndustryRules {

  String inferLayer(String industry, String name) {
    if (industry.contains("半导体") || industry.contains("电子") || industry.contains("C35")) {
      if (name.contains("测") || name.contains("精")) return "第4层 - 测试设备";
      if (name.contains("蚀")) return "第3层 - 刻蚀设备";
      if (name.contains("光")) return "第3层 - 光刻/检测设备";
    }
    if (industry.contains("医药") || industry.contains("生物")) return "第3-4层 - 创新药/医疗器械";
    return "需结合个股业务定位";
  }

  String inferChainPath(String industry, String name) {
    if (name.contains("精智达") || name.contains("华峰") || name.contains("长川")) {
      return "AI/HBM需求 → 存储原厂(三星/海力士/长江存储/长鑫) → 测试设备供应商";
    }
    if (name.contains("中微") || name.contains("北方华创")) {
      return "AI/HBM需求 → 晶圆厂 → 刻蚀/沉积设备";
    }
    return "需结合行业上下游分析";
  }

  String inferMoatType(String industry, String name) {
    if (industry.contains("半导体") || industry.contains("C35")) {
      return "地缘保护型(出口管制+国产替代政策) + 技术壁垒(高端设备研发周期3-5年)";
    }
    return "需结合个股分析";
  }

  String inferCompetitors(String industry, String name) {
    if (name.contains("精智达")) return "爱德万(日本) / 泰瑞达(美国) / 精智达(国内唯一)";
    if (name.contains("华峰")) return "泰瑞达(美国) / 爱德万(日本) / 华峰测控(国内领先)";
    if (name.contains("长川")) return "爱德万 / 泰瑞达 / 长川科技 / 分选机其他玩家";
    return "需结合行业研究";
  }

  String inferChinesePosition(String industry, String name) {
    if (industry.contains("半导体") || industry.contains("C35")) {
      return "国产替代核心受益方, 但高端产品仍由外资主导";
    }
    return "需结合行业格局";
  }

  String inferGeoAdvantage(String industry, String name) {
    if (industry.contains("半导体") || industry.contains("C35")) {
      return "美对华14nm以下设备出口管制 → 国产替代窗口期3-5年";
    }
    return "需结合地缘政治分析";
  }

  int calcMoat(String industry, String name) {
    int score = 5;
    if (industry.contains("半导体") || industry.contains("C35")) score += 3;
    if (name.contains("精智达") || name.contains("华峰")) score += 1;
    if (name.contains("唯一") || name.contains("稀缺")) score += 1;
    return Math.min(10, score);
  }
}
