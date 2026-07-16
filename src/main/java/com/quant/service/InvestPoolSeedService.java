package com.quant.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quant.entity.InvestPositionCommon;
import com.quant.entity.InvestStockPool;
import com.quant.repository.InvestPositionCommonRepository;
import com.quant.repository.InvestStockPoolRepository;
import com.quant.repository.TradeStockBasicRepository;

@Service
public class InvestPoolSeedService {

  public static final String POOL_TYPE = "tech_ai";

  private final InvestStockPoolRepository poolRepository;
  private final InvestPositionCommonRepository positionRepository;
  private final TradeStockBasicRepository stockBasicRepository;

  public InvestPoolSeedService(
      InvestStockPoolRepository poolRepository,
      InvestPositionCommonRepository positionRepository,
      TradeStockBasicRepository stockBasicRepository) {
    this.poolRepository = poolRepository;
    this.positionRepository = positionRepository;
    this.stockBasicRepository = stockBasicRepository;
  }

  @CacheEvict(value = "stockPool", allEntries = true)
  @Transactional
  public int replaceTechAiWithScreenshotPool() {
    List<SeedRow> seedRows = screenshotRows();
    List<InvestStockPool> entities = new ArrayList<>();
    for (int i = 0; i < seedRows.size(); i++) {
      entities.add(toEntity(seedRows.get(i), (i + 1) * 10));
    }
    poolRepository.deleteByPoolTypeOrUpperStockCodeIn(
        POOL_TYPE, seedRows.stream().map(row -> row.code().toUpperCase(Locale.ROOT)).toList());
    poolRepository.saveAll(entities);
    // 为每个池子创建对应的持仓记录
    List<InvestPositionCommon> positions =
        entities.stream()
            .map(
                pool -> {
                  InvestPositionCommon pos = new InvestPositionCommon();
                  pos.setStockCode(pool.getStockCode());
                  pos.setPoolType(POOL_TYPE);
                  pos.setStatus("watching");
                  pos.setPositionState("none");
                  pos.setPositionLots(BigDecimal.ZERO);
                  pos.setRealizedPnl(BigDecimal.ZERO);
                  pos.setAddCount(0);
                  pos.setTakeProfitDone(0);
                  pos.setAlertState("none");
                  return pos;
                })
            .toList();
    positionRepository.saveAll(positions);
    return entities.size();
  }

  private InvestStockPool toEntity(SeedRow row, int displayOrder) {
    InvestStockPool pool = new InvestStockPool();
    pool.setStockCode(row.code());
    pool.setStockName(row.name());
    pool.setPoolType(POOL_TYPE);
    pool.setDisplayOrder(displayOrder);
    pool.setRevenue2023(row.rev2023());
    pool.setRevenue2024(row.rev2024());
    pool.setRevenue2025(row.rev2025());
    pool.setRevenueForecastY0(row.rev2026());
    pool.setRevenueForecastY1(row.rev2027());
    pool.setRevenueForecastY2(row.rev2028());
    pool.setQ1GrossMargin(row.grossMargin());
    pool.setQ1NetMargin(row.netMargin());
    pool.setQ1RevenueGrowth(row.revenueGrowth());
    pool.setMinPs5y(row.minPs5y());
    return pool;
  }

  private List<SeedRow> screenshotRows() {
    return List.of(
        r(
            "688610.SH",
            "埃科光电",
            "2.36",
            "2.48",
            "4.40",
            "6.87",
            "10.15",
            "14.08",
            "42.97",
            "15.83",
            "95.79",
            "6.33",
            "144.70",
            "244.28"),
        r(
            "688515.SH",
            "裕太微",
            "2.74",
            "3.96",
            "6.17",
            "8.80",
            "12.27",
            "16.52",
            "42.53",
            "-30.53",
            "74.83",
            "9.41",
            "142.80",
            "74.06"),
        r(
            "688401.SH",
            "路维光电",
            "6.72",
            "8.76",
            "11.55",
            "15.76",
            "21.37",
            "29.50",
            "35.39",
            "20.88",
            "25.56",
            "3.94",
            "146.91",
            "56.95"),
        r(
            "688668.SH",
            "鼎通科技",
            "6.83",
            "10.32",
            "15.88",
            "33.98",
            "52.33",
            "74.10",
            "32.39",
            "17.59",
            "20.78",
            "2.51",
            "589.43",
            "242.42"),
        r(
            "688313.SH",
            "仕佳光子",
            "7.55",
            "10.75",
            "21.29",
            "36.10",
            "55.16",
            "73.93",
            "34.13",
            "20.15",
            "32.18",
            "2.83",
            "673.78",
            "68.48"),
        r(
            "688629.SH",
            "华丰科技",
            "9.04",
            "10.92",
            "25.28",
            "45.32",
            "68.18",
            "141.53",
            "30.34",
            "16.36",
            "56.15",
            "6.18",
            "648.16",
            "40.52"),
        r(
            "688536.SH",
            "思瑞浦",
            "10.94",
            "12.20",
            "21.42",
            "29.32",
            "37.91",
            "47.43",
            "47.66",
            "15.01",
            "66.50",
            "7.55",
            "427.15",
            "93.68"),
        r(
            "688002.SH",
            "睿创微纳",
            "35.59",
            "43.16",
            "63.04",
            "84.27",
            "106.21",
            "131.15",
            "53.40",
            "23.71",
            "71.12",
            "2.48",
            "610.47",
            "28.87"),
        r(
            "603061.SH",
            "金海通",
            "3.47",
            "4.07",
            "6.98",
            "12.57",
            "19.06",
            "27.86",
            "52.96",
            "29.06",
            "120.77",
            "7.91",
            "273.34",
            "225.35"),
        r(
            "688531.SH",
            "日联科技",
            "5.87",
            "7.39",
            "10.78",
            "16.69",
            "24.27",
            "32.68",
            "42.88",
            "14.51",
            "48.34",
            "5.41",
            "273.63",
            "152.54"),
        r(
            "688301.SH",
            "奕瑞科技",
            "18.64",
            "18.31",
            "22.51",
            "34.22",
            "44.27",
            "55.56",
            "45.83",
            "26.59",
            "42.50",
            "6.71",
            "331.19",
            "56.49"),
        r(
            "301338.SZ",
            "凯格精机",
            "7.40",
            "8.57",
            "11.56",
            "19.16",
            "26.79",
            "32.99",
            "40.10",
            "19.57",
            "72.90",
            "2.23",
            "211.60",
            "127.06"),
        r(
            "688700.SH",
            "东威科技",
            "9.09",
            "7.50",
            "10.98",
            "17.79",
            "26.68",
            "37.49",
            "37.25",
            "14.51",
            "44.47",
            "6.69",
            "242.09",
            "129.96"),
        r(
            "688630.SH",
            "芯碁微装",
            "8.29",
            "9.54",
            "14.08",
            "21.79",
            "28.90",
            "36.55",
            "40.94",
            "21.06",
            "112.48",
            "6.06",
            "519.06",
            "194.40"),
        r(
            "688025.SH",
            "杰普特",
            "12.26",
            "14.54",
            "20.74",
            "31.36",
            "43.15",
            "55.73",
            "42.96",
            "14.62",
            "92.75",
            "1.93",
            "352.22",
            "161.75"),
        r(
            "300604.SZ",
            "长川科技",
            "17.75",
            "36.42",
            "52.92",
            "78.13",
            "100.58",
            "130.11",
            "56.81",
            "26.05",
            "69.09",
            "3.42",
            "1415.51",
            "120.45"),
        r(
            "688072.SH",
            "拓荆科技",
            "27.05",
            "41.03",
            "65.19",
            "86.99",
            "113.97",
            "153.35",
            "41.69",
            "50.54",
            "56.97",
            "6.77",
            "1823.88",
            "95.70"),
        r(
            "688143.SH",
            "长盈通",
            "2.20",
            "3.31",
            "3.98",
            "6.41",
            "9.03",
            "11.77",
            "46.34",
            "6.29",
            "29.54",
            "5.65",
            "230.52",
            "256.50"),
        r(
            "301568.SZ",
            "思泰克",
            "3.68",
            "3.49",
            "4.81",
            "6.73",
            "9.08",
            "11.80",
            "50.88",
            "23.51",
            "45.56",
            "6.54",
            "90.81",
            "83.48"),
        r(
            "688378.SH",
            "奥来德",
            "5.17",
            "5.33",
            "5.77",
            "11.80",
            "15.18",
            "19.11",
            "54.33",
            "34.06",
            "53.55",
            "5.27",
            "113.98",
            "59.71"),
        r(
            "301458.SZ",
            "钧崴电子",
            "5.64",
            "6.59",
            "7.79",
            "9.65",
            "12.86",
            "17.12",
            "47.55",
            "18.45",
            "20.12",
            "8.19",
            "127.47",
            "50.60"),
        r(
            "688372.SH",
            "伟测科技",
            "7.37",
            "10.77",
            "15.75",
            "22.61",
            "30.38",
            "40.90",
            "34.96",
            "14.46",
            "71.79",
            "4.03",
            "245.06",
            "33.64"),
        r(
            "603203.SH",
            "快克智能",
            "7.93",
            "9.45",
            "10.81",
            "13.39",
            "16.12",
            "19.76",
            "49.76",
            "23.45",
            "33.09",
            "4.02",
            "178.54",
            "94.74"),
        r(
            "603005.SH",
            "晶方科技",
            "9.13",
            "11.30",
            "14.74",
            "20.27",
            "25.90",
            "32.81",
            "47.41",
            "19.65",
            "14.86",
            "7.61",
            "267.52",
            "48.95"),
        r(
            "688019.SH",
            "安集科技",
            "12.38",
            "18.35",
            "25.04",
            "33.49",
            "43.34",
            "54.56",
            "56.46",
            "28.69",
            "32.76",
            "6.58",
            "511.86",
            "34.53"),
        r(
            "688120.SH",
            "华海清科",
            "25.08",
            "34.06",
            "46.48",
            "59.44",
            "76.00",
            "96.23",
            "42.31",
            "20.58",
            "31.66",
            "6.73",
            "910.31",
            "72.10"),
        r(
            "300395.SZ",
            "菲利华",
            "20.91",
            "17.42",
            "20.16",
            "34.05",
            "48.22",
            "61.18",
            "50.74",
            "23.85",
            "53.04",
            "6.41",
            "625.56",
            "18.63"),
        r(
            "300408.SZ",
            "三环集团",
            "57.27",
            "73.75",
            "90.07",
            "117.23",
            "144.38",
            "175.07",
            "43.49",
            "29.50",
            "20.96",
            "5.28",
            "2424.37",
            "179.25"),
        r(
            "688279.SH",
            "峰岹科技",
            "4.11",
            "6.00",
            "7.74",
            "10.83",
            "14.61",
            "19.80",
            "51.82",
            "35.27",
            "46.20",
            "13.64",
            "234.37",
            "0.44"),
        r(
            "688127.SH",
            "蓝特光学",
            "7.54",
            "10.34",
            "15.36",
            "22.48",
            "29.75",
            "39.99",
            "46.51",
            "28.76",
            "76.53",
            "7.00",
            "321.63",
            "107.49"),
        r(
            "301536.SZ",
            "星宸科技",
            "20.20",
            "23.54",
            "29.72",
            "38.50",
            "49.22",
            "60.76",
            "46.05",
            "22.26",
            "49.35",
            "4.92",
            "383.72",
            "52.64"),
        r(
            "001389.SZ",
            "广合科技",
            "26.78",
            "37.34",
            "54.85",
            "84.85",
            "129.50",
            "193.19",
            "36.93",
            "20.51",
            "71.35",
            "4.08",
            "843.06",
            "118.55"));
  }

  private SeedRow r(
      String code,
      String name,
      String rev2023,
      String rev2024,
      String rev2025,
      String rev2026,
      String rev2027,
      String rev2028,
      String grossMargin,
      String netMargin,
      String revenueGrowth,
      String minPs5y,
      String currentMarketCap,
      String ytdGainPct) {
    return new SeedRow(
        code,
        name,
        bd(rev2023),
        bd(rev2024),
        bd(rev2025),
        bd(rev2026),
        bd(rev2027),
        bd(rev2028),
        bd(grossMargin),
        bd(netMargin),
        bd(revenueGrowth),
        bd(minPs5y),
        bd(currentMarketCap),
        bd(ytdGainPct));
  }

  private BigDecimal bd(String value) {
    return new BigDecimal(value);
  }

  private record SeedRow(
      String code,
      String name,
      BigDecimal rev2023,
      BigDecimal rev2024,
      BigDecimal rev2025,
      BigDecimal rev2026,
      BigDecimal rev2027,
      BigDecimal rev2028,
      BigDecimal grossMargin,
      BigDecimal netMargin,
      BigDecimal revenueGrowth,
      BigDecimal minPs5y,
      BigDecimal currentMarketCap,
      BigDecimal ytdGainPct) {}
}
