package com.quant.service.industryresearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.entity.IndustryResearchArticle;
import com.quant.entity.IndustryResearchCategory;
import com.quant.repository.IndustryResearchArticleRepository;
import com.quant.repository.IndustryResearchCategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

/**
 * 产业投研 - 初始数据 Seeder
 * 首次启动时，把 AI 算力产业链的真实内容写入数据库（与 ai-compute-dashboard.html 一致）
 */
@Slf4j
@Component
@Order(2)  // SchemaInitializer 是 @Order(1)
@RequiredArgsConstructor
public class AiComputeDataSeeder implements CommandLineRunner {

    private final IndustryResearchCategoryRepository categoryRepo;
    private final IndustryResearchArticleRepository articleRepo;
    private final IndustryResearchService researchService;
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void run(String... args) {
        // 只在没有 ai-compute 文章时才写入
        long existing = articleRepo.findAll().stream()
                .filter(a -> "ai-compute".equals(getCategoryCode(a.getCategoryId())))
                .filter(a -> "published".equals(a.getStatus()))
                .count();
        if (existing > 0) {
            log.info("[AiComputeDataSeeder] 已存在 ai-compute 文章，跳过初始化");
            return;
        }

        log.info("[AiComputeDataSeeder] 开始写入 AI 算力产业链初始文章...");
        IndustryResearchCategory cat = categoryRepo.findByCode("ai-compute").orElse(null);
        if (cat == null) {
            log.warn("[AiComputeDataSeeder] 未找到 ai-compute 产业，跳过");
            return;
        }

        IndustryResearchArticle article = new IndustryResearchArticle();
        article.setCategoryId(cat.getId());
        article.setSlug("ai-compute-chain");
        article.setTitle("AI 算力产业链深度分析");
        article.setSubtitle("NVIDIA GB200 NVL72 单机柜 BOM 拆解 + 核心结论 + Top 5 标的");
        article.setStatus("published");
        article.setVersion(1);
        article.setUpdateDate(LocalDate.of(2024, 5, 27));
        article.setSourceSummary("1171 条研报 + LightCounting + SemiAnalysis + Bernstein");
        article.setTags("AI,算力,GB200,光模块,PCB,HBM,国产替代");
        article.setViewCount(0);

        List<Map<String, Object>> sections = new ArrayList<>();
        sections.add(buildOverviewSection());
        sections.add(buildOpticalSection());
        sections.add(buildPcbSection());
        sections.add(buildHbmSection());
        sections.add(buildCpuGpuSection());
        sections.add(buildDownstreamSection());
        sections.add(buildEnergySection());
        sections.add(buildSpaceSection());
        sections.add(buildCoreSection());
        sections.add(buildValuationSection());
        sections.add(buildNewsSection());

        researchService.upsertArticle(article, sections);
        log.info("[AiComputeDataSeeder] AI 算力文章已写入 articleId={}, {} 个 Tab",
                article.getId(), sections.size());
    }

    private String getCategoryCode(Long id) {
        return categoryRepo.findById(id).map(IndustryResearchCategory::getCode).orElse(null);
    }

    /* ============ 11 Tab 内容 ============ */

    private Map<String, Object> section(String key, String title, int order, String type, Object content, String source) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("sectionKey", key);
        s.put("sectionTitle", title);
        s.put("sectionOrder", order);
        s.put("contentType", type);
        s.put("content", content);
        s.put("source", source);
        return s;
    }

    private Map<String, Object> buildOverviewSection() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("subtitle", "AI 算力产业总览 · GB200 NVL72 单机柜 BOM 拆解 + 核心结论 + Top 5 标的");
        c.put("sourceSummary", "1171 条研报 + LightCounting + SemiAnalysis + Bernstein");
        c.put("metrics", List.of(
                Map.of("label", "L1 模块评分", "value", 63.8, "unit", "", "desc", "估值分位 < 50%", "badge", "OK"),
                Map.of("label", "产业链环节", "value", 14, "unit", "", "desc", "5 级架构", "badge", "OK"),
                Map.of("label", "核心标的", "value", 15, "unit", "家", "desc", "6 月深度覆盖", "badge", "OK"),
                Map.of("label", "单机柜 BOM", "value", "$3", "unit", "M", "desc", "NVL72 · 72×B200 GPU", "badge", "OK")
        ));
        c.put("conclusions", List.of(
                Map.of("level", "ok", "tag", "OK", "text", "PCB/HDI 估值相对合理，产能壁垒高 + 业绩确定性强"),
                Map.of("level", "ok", "tag", "OK", "text", "北美云厂商资本开支 Q1 环比 +60%，全年指引超预期"),
                Map.of("level", "ok", "tag", "OK", "text", "UBB 全面升级，GB200 单柜价值量翻倍"),
                Map.of("level", "warn", "tag", "RISK", "text", "光模块龙头 ~2025 预测 PE 42x，业绩兑现压力大"),
                Map.of("level", "warn", "tag", "RISK", "text", "国产 GPU ~PE 241x，估值显著高于海外对标"),
                Map.of("level", "warn", "tag", "RISK", "text", "国产 AI 芯片 ~PE 60x，需警惕涨价信仰破局")
        ));
        c.put("bomBars", List.of(
                Map.of("label", "GPU 模组 (72×B200)", "percentage", 72, "value", "~$2,160K"),
                Map.of("label", "PCB / HDI", "percentage", 6, "value", "~$179K"),
                Map.of("label", "液冷系统", "percentage", 4, "value", "~$120K"),
                Map.of("label", "光模块", "percentage", 4, "value", "~$120K"),
                Map.of("label", "CPU", "percentage", 3, "value", "~$90K"),
                Map.of("label", "NVLink / 网线", "percentage", 3, "value", "~$90K"),
                Map.of("label", "电源", "percentage", 3, "value", "~$90K"),
                Map.of("label", "机柜 / 装配", "percentage", 5, "value", "~$151K")
        ));
        c.put("tables", List.of(Map.of(
                "name", "Top 5 核心标的（综合评分）",
                "headers", List.of("标的", "代码", "环节", "综合评分", "不可替代性", "PE (TTM)"),
                "rows", List.of(
                        List.of("沪电股份", "002463", "PCB", "82", "高 · UBB 一供", "28x"),
                        List.of("胜宏科技", "300476", "HDI", "79", "高 · 英伟达 HDI 一供", "32x"),
                        List.of("中际旭创", "300308", "光模块", "77", "高 · 全球 800G/1.6T 一供", "35x"),
                        List.of("工业富联", "601138", "系统集成", "74", "中 · GB200 整柜代工", "22x"),
                        List.of("寒武纪", "688256", "国产 AI 芯片", "68", "中 · 思元 590 推理主控", "~241x")
                )
        )));
        return section("overview", "总览", 1, "mixed", c, "AI 摘要 1171 篇研报 + LightCounting + SemiAnalysis + Bernstein");
    }

    private Map<String, Object> buildOpticalSection() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("subtitle", "光模块深度分析 · 1.6T BOM 拆解 + 中际旭创/天孚通信/源杰科技");
        c.put("metrics", List.of(
                Map.of("label", "全球 1.6T 市场", "value", "~$30", "unit", "亿", "desc", "2025E，2026E 翻倍"),
                Map.of("label", "中际旭创全球份额", "value", "50%+", "desc", "800G / 1.6T 双代际领跑"),
                Map.of("label", "单只 BOM", "value", "$850–1,500", "desc", "较 800G 单价 +150-180%")
        ));
        c.put("tables", List.of(Map.of(
                "name", "1.6T 光模块 BOM 拆解",
                "headers", List.of("子部件", "占比", "金额 ($)", "海外供应商", "A 股标的"),
                "rows", List.of(
                        List.of("EML 激光器芯片", "25-30%", "$300-400", "Coherent / Lumentum", "源杰科技"),
                        List.of("PAM4 DSP 芯片", "20-25%", "$200-350", "Broadcom / Marvell", "—"),
                        List.of("光纤组件", "8-12%", "$80-150", "YOFC / Coherent", "—"),
                        List.of("TIA / Driver", "5-8%", "$50-100", "Semtech / MACOM", "天孚通信"),
                        List.of("柔性 PCB", "5-7%", "$50-90", "—", "沪电 / 深南"),
                        List.of("MPO / MTP", "4-6%", "$40-80", "US Conec / Senko", "—"),
                        List.of("封装与测试", "10-15%", "$100-200", "Fabrinet", "中际旭创")
                )
        )));
        c.put("stockCards", List.of(
                Map.of("name", "中际旭创", "code", "300308.SZ", "pe", "35x", "marketCap", "1,250",
                        "logic", "全球光模块绝对龙头，800G 份额 50%+，1.6T 率先量产交付北美四大云",
                        "score", 90, "irreplaceablePct", 90),
                Map.of("name", "天孚通信", "code", "300394.SZ", "pe", "40x", "marketCap", "540",
                        "logic", "光模块无源 + 有源封装平台型供应商，深度绑定中际旭创 / Coherent",
                        "score", 78, "irreplaceablePct", 78)
        ));
        return section("optical", "光模块", 2, "mixed", c, "Coherent / Broadcom / LightCounting + Kimi 提炼");
    }

    private Map<String, Object> buildPcbSection() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("subtitle", "PCB/HDI 深度分析 · 类型价值量 + 价值拆解 + 标的卡片");
        c.put("metrics", List.of(
                Map.of("label", "UBB 单价", "value", "$171", "unit", "K", "desc", "20-26 层 M8 材料"),
                Map.of("label", "层数升级", "value", "20→44", "unit", "层", "desc", "工艺壁垒极高"),
                Map.of("label", "PCB 单 GPU 价值", "value", "$100→300", "desc", "材料 + 工艺 + 尺寸三重升级"),
                Map.of("label", "沪电 + 胜宏合计份额", "value", "~60%", "desc", "UBB + HDI 国内双寡头")
        ));
        c.put("tables", List.of(Map.of(
                "name", "AI 服务器 PCB 类型与价值量",
                "headers", List.of("类型", "层数", "材料", "单机柜价值", "海外", "A 股主供"),
                "rows", List.of(
                        List.of("UBB 通用底板", "20-26 层", "M8 Ultra Low Loss", "~$90K", "—", "胜宏科技"),
                        List.of("GPU 主板 HDI", "12-16 层", "M6/M7", "~$54K", "—", "沪电股份 / 胜宏"),
                        List.of("交换机背板", "30-40 层", "M8", "~$15K", "—", "沪电股份"),
                        List.of("Switch 主板", "16-20 层", "M6", "~$8K", "—", "沪电 / 胜宏"),
                        List.of("电源 PCB", "8-12 层", "FR4 + 厚铜", "~$4K", "—", "深南电路 / 兴森科技")
                )
        )));
        c.put("stockCards", List.of(
                Map.of("name", "沪电股份", "code", "002463.SZ", "pe", "28x", "marketCap", "580",
                        "logic", "北美云客户交换机背板主供，44 层工艺国内最强",
                        "score", 82, "irreplaceablePct", 90),
                Map.of("name", "胜宏科技", "code", "300476.SZ", "pe", "32x", "marketCap", "340",
                        "logic", "NVIDIA UBB 一供，mSAP 产能全球第一",
                        "score", 79, "irreplaceablePct", 90),
                Map.of("name", "深南电路", "code", "002916.SZ", "pe", "35x", "marketCap", "410",
                        "logic", "通信 + 服务器 PCB 综合龙头，IC 载板布局完整",
                        "score", 68, "irreplaceablePct", 70)
        ));
        return section("pcb", "PCB/HDI", 3, "mixed", c, "Prismark / 沪电年报 + Kimi 提炼");
    }

    private Map<String, Object> buildHbmSection() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("subtitle", "HBM/存储深度分析 · 成本拆解 + 代际演进 + A 股关联");
        c.put("note", "A 股没有 HBM 直接标的。间接受益环节：封装测试（通富微电 / 长川科技）+ 设备 + TSV 材料");
        c.put("metrics", List.of(
                Map.of("label", "SK Hynix 全球份额", "value", "~70%", "desc", "HBM3 / HBM3e 绝对领先"),
                Map.of("label", "HBM3e 单颗成本", "value", "~$280", "desc", "12 层堆叠，单颗 24GB"),
                Map.of("label", "B200 单卡 HBM 颗数", "value", "8", "unit", "颗", "desc", "总容量 192GB"),
                Map.of("label", "2025E HBM 市场", "value", "~$80", "unit", "B", "desc", "YoY +90%")
        ));
        c.put("bomBars", List.of(
                Map.of("label", "DRAM Die (12 层)", "percentage", 55, "value", "~$155"),
                Map.of("label", "TSV 工艺", "percentage", 15, "value", "~$42"),
                Map.of("label", "先进封装", "percentage", 12, "value", "~$34"),
                Map.of("label", "测试", "percentage", 8, "value", "~$22"),
                Map.of("label", "基板 / PCB", "percentage", 6, "value", "~$17"),
                Map.of("label", "其他", "percentage", 4, "value", "~$10")
        ));
        c.put("tables", List.of(Map.of(
                "name", "HBM 代际演进",
                "headers", List.of("代际", "堆叠", "单颗容量", "带宽 (GB/s)", "单颗 $"),
                "rows", List.of(
                        List.of("HBM2", "8 层", "8 GB", "410", "~$50"),
                        List.of("HBM2e", "8/12 层", "16 GB", "460", "~$90"),
                        List.of("HBM3", "8/12 层", "16-24 GB", "820", "~$160"),
                        List.of("HBM3e", "12 层", "24-36 GB", "1,200", "~$280"),
                        List.of("HBM4 (2026)", "16 层", "48 GB", "1,800", "~$450")
                )
        )));
        c.put("stockCards", List.of(
                Map.of("name", "通富微电", "code", "002156.SZ", "pe", "42x", "marketCap", "380",
                        "logic", "HBM 封装 + AMD CPU/GPU 封测主供",
                        "score", 72, "irreplaceablePct", 75),
                Map.of("name", "长川科技", "code", "300604.SZ", "pe", "55x", "marketCap", "260",
                        "logic", "半导体测试设备龙头，HBM 测试机国产唯一",
                        "score", 70, "irreplaceablePct", 80),
                Map.of("name", "雅克科技", "code", "002409.SZ", "pe", "38x", "marketCap", "180",
                        "logic", "前驱体材料 + TSV 深孔填充材料主供",
                        "score", 65, "irreplaceablePct", 70)
        ));
        return section("hbm", "HBM/存储", 4, "mixed", c, "SK Hynix / Samsung / Micron 财报 + Kimi 提炼");
    }

    private Map<String, Object> buildCpuGpuSection() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("subtitle", "CPU/GPU 深度分析 · 推理时代 CPU 角色提升 + 国产 AI 芯片竞争格局");
        c.put("metrics", List.of(
                Map.of("label", "2024 中国 AI 芯片市场", "value", "1,206", "unit", "亿", "desc", "YoY +85%"),
                Map.of("label", "2025 国产化率", "value", "46%", "desc", "从 30% → 46% 跃升"),
                Map.of("label", "2025 国产 AI 芯片交付", "value", "165", "unit", "万颗", "desc", "训练 35 万 + 推理 130 万"),
                Map.of("label", "华为昇腾份额", "value", "23%", "desc", "国产绝对龙头")
        ));
        c.put("conclusions", List.of(
                Map.of("level", "info", "tag", "TREND", "text", "Agent 应用爆发 → Tokenization 处理激增 → KV Cache 管理需求 → CPU 协同地位上升"),
                Map.of("level", "info", "tag", "DATA", "text", "单次 Agent 调用平均 4,200 次 CPU 指令调度，是传统推理的 8 倍"),
                Map.of("level", "ok", "tag", "受益", "text", "海光信息（x86 永久授权）+ 寒武纪（自研指令集）双线受益")
        ));
        c.put("tables", List.of(Map.of(
                "name", "国产 AI 芯片竞争格局",
                "headers", List.of("厂商", "代表产品", "份额", "对标 H100", "生态"),
                "rows", List.of(
                        List.of("华为昇腾", "910C / 910B", "23%", "~60% (FP16)", "CANN"),
                        List.of("寒武纪", "思元 590 / 370", "9%", "~50%", "自研 MLU"),
                        List.of("海光信息", "深算 DCU", "8%", "~55%", "ROCm 兼容"),
                        List.of("壁仞科技", "BR104", "4%", "~45%", "BIRENSUPA"),
                        List.of("摩尔线程", "MTT S4000", "3%", "~35%", "MUSA"),
                        List.of("天数智芯", "天垓 100", "2%", "~40%", "自研")
                )
        )));
        c.put("stockCards", List.of(
                Map.of("name", "海光信息", "code", "688041.SH", "pe", "78x", "marketCap", "1,680",
                        "logic", "x86 永久授权 + 深算 DCU 国内唯一对标 AMD CDNA",
                        "score", 76, "irreplaceablePct", 90),
                Map.of("name", "寒武纪", "code", "688256.SH", "pe", "~241x", "marketCap", "2,800",
                        "logic", "思元 590 训练端突破 + 思元 370 推理放量；估值偏高",
                        "score", 68, "irreplaceablePct", 75)
        ));
        return section("cpu-gpu", "CPU/GPU", 5, "mixed", c, "IDC + TrendForce + 国产芯片厂财报");
    }

    private Map<String, Object> buildDownstreamSection() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("subtitle", "算力下游企业 · 全球云厂商资本开支 + 大模型公司估值");
        c.put("tables", List.of(Map.of(
                "name", "全球云厂商 2024 资本开支",
                "headers", List.of("云厂商", "Q1 实际 ($B)", "全年指引 ($B)", "YoY", "AI 占比"),
                "rows", List.of(
                        List.of("Amazon (AWS)", "44.2", "~210", "+60%", "~55%"),
                        List.of("Microsoft", "31.9", "~190", "+57%", "~60%"),
                        List.of("Google", "23.9", "~110", "+49%", "~50%"),
                        List.of("Meta", "13.7", "~95", "+42%", "~45%"),
                        List.of("四大合计", "113.7", "~605", "+52%", "—")
                )
        )));
        c.put("tables", List.of(Map.of(
                "name", "海外大模型公司估值",
                "headers", List.of("公司", "估值 ($B)", "最新一轮", "2024 营收"),
                "rows", List.of(
                        List.of("OpenAI", "~840", "2024-02", "~$3.5B"),
                        List.of("Anthropic", "~80", "2024-03", "~$1.0B"),
                        List.of("xAI", "~50", "2024-05", "~$0.1B"),
                        List.of("Cohere", "~5", "2024-06", "~$0.2B")
                )
        )));
        c.put("note", "数据可能滞后，建议接入 API 实时更新");
        return section("downstream", "下游企业", 6, "mixed", c, "Bloomberg / Reuters + 公司财报");
    }

    private Map<String, Object> buildEnergySection() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("subtitle", "算力能源 · AI 的尽头是能源 · 功耗演进 + 液冷革命 + 中美能源对比");
        c.put("metrics", List.of(
                Map.of("label", "GPU 功耗演进", "value", "700→1,400", "unit", "W", "desc", "A100 → B200"),
                Map.of("label", "2026E 美国数据中心", "value", "580", "unit", "TWh", "desc", "占总用电 ~6%"),
                Map.of("label", "2030E 中国数据中心", "value", "7,000", "unit", "亿 kWh", "desc", "占总用电 ~10%"),
                Map.of("label", "单柜功耗", "value", "120", "unit", "kW", "desc", "GB200 NVL72")
        ));
        c.put("chart", Map.of("chartType", "line", "data", Map.of(
                "labels", List.of("V100(2017)", "A100(2020)", "H100(2022)", "B200(2024)", "B300(2025E)", "B400(2026E)"),
                "values", List.of(300, 400, 700, 1000, 1200, 1400),
                "label", "单卡功耗 (W)"
        )));
        c.put("tables", List.of(Map.of(
                "name", "风冷 vs 液冷对比",
                "headers", List.of("指标", "风冷", "液冷"),
                "rows", List.of(
                        List.of("PUE", "1.4-1.6", "1.05-1.15"),
                        List.of("制冷能耗占比", "35-45%", "5-10%"),
                        List.of("单机柜最高功耗", "~40 kW", "~120 kW"),
                        List.of("Capex 溢价", "基准", "+30-50%"),
                        List.of("Opex 节约", "—", "~40%")
                )
        )));
        c.put("stockCards", List.of(
                Map.of("name", "英维克", "code", "002837.SZ", "pe", "42x", "marketCap", "220",
                        "logic", "液冷全栈解决方案龙头，冷板式 + 浸没式双路径",
                        "score", 75, "irreplaceablePct", 80),
                Map.of("name", "高澜股份", "code", "300499.SZ", "pe", "58x", "marketCap", "95",
                        "logic", "液冷板 + CDU 主供",
                        "score", 65, "irreplaceablePct", 70)
        ));
        return section("energy", "算力能源", 7, "mixed", c, "IEA + SemiAnalysis + 国网能源研究院");
    }

    private Map<String, Object> buildSpaceSection() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("subtitle", "太空算力 · 终极解决方案 · 中美竞赛 + 产业链");
        c.put("metrics", List.of(
                Map.of("label", "地面瓶颈 #1 能源", "value", "-30%", "desc", "数据中心制冷损耗下界"),
                Map.of("label", "地面瓶颈 #2 散热", "value", "~40°C", "desc", "散热终极限"),
                Map.of("label", "地面瓶颈 #3 土地", "value", "120", "unit", "kW/柜", "desc", "一线城市受限"),
                Map.of("label", "太阳辐射 GEO", "value", "1,361", "unit", "W/㎡", "desc", "无大气损耗，无夜间")
        ));
        c.put("conclusions", List.of(
                Map.of("level", "ok", "tag", "能源", "text", "太阳辐射 1,361 W/㎡（无大气损耗、无夜间中断 GEO 轨道），是地面光伏的 5-8 倍"),
                Map.of("level", "ok", "tag", "散热", "text", "太空背景温度接近绝对零度，散热效率理论无限"),
                Map.of("level", "ok", "tag", "解耦", "text", "能源 + 散热 + 土地三大地面瓶颈全部解除"),
                Map.of("level", "info", "tag", "演进", "text", "天地协同 → 天地互备 → 天基主算：预计 2030 年开始天基主算试点")
        ));
        c.put("tables", List.of(Map.of(
                "name", "全球竞赛 · SpaceX vs 中国",
                "headers", List.of("方案", "主体", "进展", "算力规模"),
                "rows", List.of(
                        List.of("Starlink + xAI", "SpaceX / xAI", "2024 启动", "100K H100 级"),
                        List.of("星网 + 国智", "中国星网", "2024 试点", "10K 国产 GPU"),
                        List.of("Project Kuiper", "Amazon", "2027 部署", "—"),
                        List.of("之江实验室", "中国之江", "2024 试验", "—")
                )
        )));
        return section("space", "太空算力", 8, "mixed", c, "SpaceX / 中国星网 + 行业研究");
    }

    private Map<String, Object> buildCoreSection() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("subtitle", "核心标的深度 · 中际旭创");
        c.put("metrics", List.of(
                Map.of("label", "2024E 营收", "value", "195", "unit", "亿", "desc", "YoY +120%"),
                Map.of("label", "2024E 净利润", "value", "48", "unit", "亿", "desc", "YoY +262%"),
                Map.of("label", "PE (TTM)", "value", "35x", "desc", "Forward PE 24x"),
                Map.of("label", "PEG", "value", "0.42", "desc", "成长性匹配估值")
        ));
        c.put("chart", Map.of("chartType", "doughnut", "data", Map.of(
                "labels", List.of("800G 光模块", "1.6T 光模块", "400G 及以下", "相干/ZR", "其他"),
                "values", List.of(58, 18, 14, 6, 4),
                "label", "营收结构 2024E"
        )));
        c.put("conclusions", List.of(
                Map.of("level", "ok", "tag", "份额", "text", "800G 全球份额 50%+，1.6T 率先量产，深度绑定 NVIDIA / Google / Meta"),
                Map.of("level", "ok", "tag", "技术", "text", "自研硅光 + EML + 自有封装线（业内唯一），技术成本领先 15-20%"),
                Map.of("level", "warn", "tag", "客户", "text", "前五大客户占比 ~80%，NVIDIA 单一客户 ~40%"),
                Map.of("level", "warn", "tag", "价格", "text", "光模块价格年降 8-15%，需持续 1.6T 升级对冲")
        ));
        c.put("tables", List.of(Map.of(
                "name", "关键财务",
                "headers", List.of("指标", "2022A", "2023A", "2024E", "2025E"),
                "rows", List.of(
                        List.of("营收（亿）", "96.4", "88.5", "195.0", "320.0"),
                        List.of("营收 YoY", "+25%", "-8%", "+120%", "+64%"),
                        List.of("毛利率", "27%", "30%", "33%", "34%"),
                        List.of("净利润（亿）", "12.2", "13.2", "48.0", "82.0"),
                        List.of("净利润 YoY", "+39%", "+8%", "+262%", "+71%")
                )
        )));
        return section("core-stock", "核心标的", 9, "mixed", c, "公司年报 + Kimi 1171 篇研报");
    }

    private Map<String, Object> buildValuationSection() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("subtitle", "估值全景 · A 股核心标的宽表");
        c.put("tables", List.of(Map.of(
                "name", "A 股核心标的估值汇总",
                "headers", List.of("标的", "代码", "环节", "市值(亿)", "PE (TTM)", "PE (2025E)", "PB", "PEG"),
                "rows", List.of(
                        List.of("中际旭创", "300308", "光模块", "1,250", "35x", "24x", "6.8", "0.42"),
                        List.of("沪电股份", "002463", "PCB", "580", "28x", "22x", "4.5", "0.68"),
                        List.of("胜宏科技", "300476", "HDI", "340", "32x", "20x", "5.2", "0.45"),
                        List.of("工业富联", "601138", "系统集成", "4,200", "22x", "18x", "3.1", "0.55"),
                        List.of("天孚通信", "300394", "光模块器件", "540", "40x", "28x", "7.4", "0.72"),
                        List.of("源杰科技", "688498", "光芯片", "220", "85x", "45x", "8.9", "0.95"),
                        List.of("深南电路", "002916", "PCB / 载板", "410", "35x", "26x", "4.8", "0.82"),
                        List.of("寒武纪", "688256", "国产 AI 芯片", "2,800", "~241x", "~120x", "28.5", "2.10"),
                        List.of("海光信息", "688041", "DCU", "1,680", "78x", "48x", "12.6", "0.88"),
                        List.of("通富微电", "002156", "封测", "380", "42x", "28x", "3.6", "0.75"),
                        List.of("长川科技", "300604", "测试设备", "260", "55x", "38x", "7.8", "0.92"),
                        List.of("英维克", "002837", "液冷", "220", "42x", "28x", "5.8", "0.65"),
                        List.of("生益电子", "688183", "CCL", "180", "38x", "26x", "3.9", "0.70"),
                        List.of("景旺电子", "603228", "PCB", "280", "26x", "20x", "3.4", "0.62")
                )
        )));
        c.put("conclusions", List.of(
                Map.of("level", "info", "tag", "估值", "text", "PCB/HDI 整体估值合理（PE < 30x）"),
                Map.of("level", "ok", "tag", "光模块", "text", "龙头估值偏高但 PEG 仍 < 1"),
                Map.of("level", "warn", "tag", "国产", "text", "国产 AI 芯片估值显著高估（寒武纪 PEG 2.10）")
        ));
        return section("valuation", "估值全景", 10, "mixed", c, "Wind / 同花顺实时报价");
    }

    private Map<String, Object> buildNewsSection() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("subtitle", "24h AI 新闻雷达 · 全球聚合");
        c.put("metrics", List.of(
                Map.of("label", "过去 24h 收录", "value", "147", "unit", "条", "desc", "AI 算力关键词过滤后"),
                Map.of("label", "重要级别 HIGH", "value", "12", "unit", "条", "desc", "影响产业链格局"),
                Map.of("label", "信源覆盖", "value", "38", "unit", "家", "desc", "海外 22 + 国内 16")
        ));
        c.put("news", List.of(
                Map.of("time", "15:38", "source", "Reuters", "title", "NVIDIA Blackwell B200 量产爬坡顺利，Q3 出货量超预期 20%"),
                Map.of("time", "14:55", "source", "Bloomberg", "title", "SK Hynix 拿下 NVIDIA HBM4 独家首发权，2025 H1 量产"),
                Map.of("time", "13:22", "source", "FT", "title", "Anthropic 估值新一轮融资升至 800 亿，Lightspeed 领投"),
                Map.of("time", "12:08", "source", "WSJ", "title", "Google 内部上调 2024 AI Capex 至 1,100 亿美元"),
                Map.of("time", "11:45", "source", "财联社", "title", "中际旭创 1.6T 光模块获北美客户追加订单"),
                Map.of("time", "10:32", "source", "The Information", "title", "xAI 计划 2025 年建成 100K H100 集群"),
                Map.of("time", "09:18", "source", "证券时报", "title", "沪电股份中标北美云客户 UBB 大单，金额超 20 亿"),
                Map.of("time", "15:12", "source", "36Kr", "title", "字节跳动豆包大模型日 Token 调用量突破 5,000 亿"),
                Map.of("time", "14:42", "source", "The Verge", "title", "OpenAI 推出 GPT-4o 多模态升级版，推理成本再降 50%")
        ));
        c.put("topKeywords", List.of(
                Map.of("keyword", "#NVIDIA Blackwell", "count", 42),
                Map.of("keyword", "#HBM4", "count", 28),
                Map.of("keyword", "#1.6T 光模块", "count", 24),
                Map.of("keyword", "#UBB", "count", 19),
                Map.of("keyword", "#国产 GPU", "count", 17),
                Map.of("keyword", "#昇腾", "count", 15),
                Map.of("keyword", "#Capex 上修", "count", 14),
                Map.of("keyword", "#液冷", "count", 11)
        ));
        return section("news", "AI 新闻", 11, "news", c, "Tavily Search API + 财联社 + Reuters + FT + WSJ");
    }
}