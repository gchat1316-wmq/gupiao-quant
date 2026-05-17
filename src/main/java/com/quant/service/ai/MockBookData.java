package com.quant.service.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * 内置示例书籍的知识体系 (在 AI 未配置 / 调用失败时使用)。
 */
public final class MockBookData {

    private MockBookData() {}

    public static boolean matches(String title) {
        if (title == null) return false;
        return title.contains("投资中最简单的事")
                || title.contains("投资中最简单")
                || title.toLowerCase().contains("investing");
    }

    public static ExtractedNode investingSimpleThings() {
        ExtractedNode root = ExtractedNode.node(
                "《投资中最简单的事》核心知识体系",
                "本书是邱国鹭对其多年价值投资实践的系统总结,围绕\"投资核心理念 → 分析框架 → 风险管理 → 策略与周期 → 心理与智慧\"五大支柱,讨论投资中真正重要、可执行、并且不变的事情。",
                "价值投资 = 在估值合理或低估的位置,买入具有持续竞争优势的好公司,长期持有获取企业内在价值增长的收益。"
        );

        // 1. 投资核心理念
        ExtractedNode core = ExtractedNode.node(
                "投资核心理念",
                "本章讨论价值投资的底层世界观:重视估值、坚持便宜、敢于逆向。强调投资真正难的不是技巧,而是认知与坚持。",
                "便宜+优质+逆向 = 长期超额收益的三个最朴素来源。",
                valueVsGrowth(),
                cheapIsKing(),
                contrarian()
        );

        // 2. 投资分析框架
        ExtractedNode framework = ExtractedNode.node(
                "投资分析框架",
                "把投资抽象为可重复执行的分析流程:回答\"为什么便宜\"\"为什么好\"\"为什么现在买\"三个问题,辅以波特五力、杜邦、估值三套工具,并叠加行业选择。",
                "估值 + 品质 + 时机,缺一不可。",
                threeBasicQuestions(),
                basicTools(),
                industryAndCompetition()
        );

        // 3. 投资风险与管理
        ExtractedNode risk = ExtractedNode.node(
                "投资风险与管理",
                "辨别价值陷阱与成长陷阱、区分真假风险、运用安全边际,并理解价值投资本身的局限,知道何时止损。",
                "风险管理的核心,不是预测风险,而是为意外预留缓冲——这就是安全边际。",
                valueAndGrowthTraps(),
                realVsFakeRisk(),
                stopLossLimits()
        );

        // 4. 投资策略与市场周期
        ExtractedNode cycle = ExtractedNode.node(
                "投资策略与市场周期",
                "理解四种周期、三种杠杆与板块轮动逻辑;在中国经济\"新常态\"背景下,识别未来 10 年的投资路向。",
                "策略服从周期,资金跟随杠杆;新常态下,优质龙头与消费/科技确定性更高。",
                cyclesAndLeverages(),
                tenYearOutlook()
        );

        // 5. 投资心理学与智慧
        ExtractedNode psy = ExtractedNode.node(
                "投资心理学与智慧",
                "梳理常见心理误区,提炼经典投资格言,用\"树动 / 风动 / 心动\"框架理解短中长期股价驱动因素。",
                "战胜市场,先战胜自己。",
                psychologyTraps(),
                wisdom(),
                treeWindHeart()
        );

        root.getChildren().addAll(List.of(core, framework, risk, cycle, psy));
        return root;
    }

    // ===== Level 2/3 builders =====

    private static ExtractedNode valueVsGrowth() {
        return ExtractedNode.node(
                "价值投资与成长投资",
                "比较两种主流投资理念,价值与成长并非对立,而是\"算账方式\"不同。",
                "价值看现金流折现,成长看复合增长;最终都要回到企业内在价值。",
                ExtractedNode.leaf("价值投资的本质与优势",
                        "价值投资强调以低于内在价值的价格买入,赚取价值回归与企业成长两份收益。它的优势在于安全边际显著、对认知要求清晰、长期复利更稳定。"),
                ExtractedNode.leaf("成长投资的特点与挑战",
                        "成长投资追求高增长带来的估值与利润双升;挑战在于增长难以持续、估值容易透支、回撤往往剧烈,需要对行业判断极准。"),
                ExtractedNode.leaf("价值投资与成长投资的殊途同归",
                        "无论价值还是成长,最终核心都是\"以合理价格买入优秀企业\";伟大公司终将在某一阶段同时具备\"便宜\"与\"成长\"两种属性。")
        );
    }

    private static ExtractedNode cheapIsKing() {
        return ExtractedNode.node(
                "便宜是硬道理",
                "强调估值是投资中唯一可以\"事前把握\"的变量,低估值天然提供安全边际。",
                "如果不知道未来,至少要知道现在不贵。",
                ExtractedNode.leaf("估值是投资中最可把握的要素",
                        "未来很难预测,但当前的 PE、PB、股息率、自由现金流收益率是确定的;在不确定中找确定,先从估值开始。"),
                ExtractedNode.leaf("低估值提供安全边际",
                        "估值低意味着即便判断有误,亏损幅度也有限;估值高则错一次就可能腰斩——这是非对称的风险收益。"),
                ExtractedNode.leaf("价值股长期跑赢成长股是规律",
                        "全球长期数据(法马、夏普等学者研究)均显示,低估值组合长期跑赢高估值组合,这是市场对\"风险溢价\"与\"过度乐观\"的修正。")
        );
    }

    private static ExtractedNode contrarian() {
        return ExtractedNode.node(
                "人弃我取与逆向投资",
                "讨论逆向投资的本质、关键条件以及在哪些行业更适用。",
                "贪婪与恐惧的相反端,往往是赔率最高的时刻。",
                ExtractedNode.leaf("逆向投资是品格与超额收益来源",
                        "逆向投资本质上是对人性弱点的克服:它需要在群体悲观时仍能独立判断,赚取的是市场情绪与基本面的偏差收益。"),
                ExtractedNode.leaf("逆向投资的三个关键条件",
                        "1) 基本面未恶化只是情绪杀估值;2) 公司不会破产、行业不会消失;3) 有足够时间等待价值回归。"),
                ExtractedNode.leaf("逆向投资的行业适用性",
                        "周期股、消费龙头、品牌护城河行业适合逆向;商业模式正在崩坏、技术被替代的行业不适合逆向(可能是价值陷阱)。")
        );
    }

    private static ExtractedNode threeBasicQuestions() {
        return ExtractedNode.node(
                "投资的三个基本问题",
                "把任何一个买入决定,都压缩成 3 个必答题。回答不了任意一题,就不该下单。",
                "估值 + 品质 + 时机,缺一不可。",
                ExtractedNode.leaf("问题1: 估值 (为什么便宜)",
                        "回答\"为什么便宜\":是市场偏见、行业错配、短期利空,还是基本面已经永久受损?只有前者才值得买入。"),
                ExtractedNode.leaf("问题2: 品质 (为什么好)",
                        "回答\"为什么好\":公司的护城河、ROIC、管理层、长期空间、商业模式可持续性。"),
                ExtractedNode.leaf("问题3: 时机 (为什么现在买)",
                        "回答\"为什么现在买\":催化剂、行业拐点、估值底部信号——避免\"价值股一拿三年还在跌\"。")
        );
    }

    private static ExtractedNode basicTools() {
        return ExtractedNode.node(
                "投资分析的基本工具",
                "三套足以应付 90% 场景的基础工具。",
                "工具不是用来炫技,而是用来落地三个基本问题。",
                ExtractedNode.leaf("波特五力分析",
                        "用于评估行业竞争格局:现有竞争者、潜在进入者、替代品、上游议价、下游议价。判断\"为什么好\"的核心工具之一。"),
                ExtractedNode.leaf("杜邦分析",
                        "把 ROE 拆解为净利率 × 周转率 × 杠杆,识别公司盈利能力到底来自哪里、可持续性如何。"),
                ExtractedNode.leaf("估值分析",
                        "PE、PB、PS、DCF、股息折现等多套估值方法交叉验证,避免单一指标失真。")
        );
    }

    private static ExtractedNode industryAndCompetition() {
        return ExtractedNode.node(
                "行业选择与竞争格局",
                "选对行业比选对公司更重要,行业的竞争格局决定了平均回报上限。",
                "行业β > 公司α,选对赛道是大概率事件的开始。",
                ExtractedNode.leaf("好行业的标准",
                        "需求稳定、行业增速合理、竞争格局清晰、龙头优势可持续、ROIC 高于资金成本。"),
                ExtractedNode.leaf("行业集中度与投资回报",
                        "集中度提升的行业,龙头通常获得超额回报;过度分散且无品牌护城河的行业难出长牛股。"),
                ExtractedNode.leaf("\"数月亮\"策略",
                        "选行业时\"数月亮\"而不是\"数星星\":只投行业里能数得过来、最大、最亮的几个龙头。")
        );
    }

    private static ExtractedNode valueAndGrowthTraps() {
        return ExtractedNode.node(
                "价值陷阱与成长陷阱",
                "便宜不等于好,高成长也不等于好;两类陷阱毁掉的财富甚至超过牛市赚到的。",
                "价值陷阱:便宜得有原因;成长陷阱:成长被透支。",
                ExtractedNode.leaf("价值陷阱的常见类型",
                        "夕阳行业、被技术颠覆的行业、周期顶点的强周期股、公司治理崩坏的低估值股——便宜只是表象,内在价值还在持续下滑。"),
                ExtractedNode.leaf("成长陷阱的常见类型",
                        "伪需求、伪壁垒、风口型创业、估值已透支未来 5-10 年增长的高估值成长股。"),
                ExtractedNode.leaf("两类陷阱的共性",
                        "都是把短期信号当作长期规律,缺乏对商业模式与护城河的本质审视。")
        );
    }

    private static ExtractedNode realVsFakeRisk() {
        return ExtractedNode.node(
                "真假风险与安全边际",
                "市场上每天讨论的\"风险\",大多是噪声;真正的风险是\"本金永久性损失\"。",
                "波动 ≠ 风险;不可恢复的损失才是风险。",
                ExtractedNode.leaf("风险的多种维度",
                        "包括估值风险、流动性风险、行业风险、公司治理风险、宏观系统风险。理解风险的多维度才能恰当配置。"),
                ExtractedNode.leaf("安全边际的特点",
                        "在价格 vs 价值之间留出缓冲;留得越宽,容错越大。"),
                ExtractedNode.leaf("通过安全边际管理风险",
                        "通过低估值买入 + 分散持仓 + 避免杠杆,把不可控变成可控。")
        );
    }

    private static ExtractedNode stopLossLimits() {
        return ExtractedNode.node(
                "止损与价值投资局限性",
                "价值投资不是\"死扛\";同样有其适用边界。",
                "持有的前提是基本面没变;基本面变了,再便宜也要卖。",
                ExtractedNode.leaf("止损与卖出原则",
                        "1) 基本面变坏;2) 投资逻辑被证伪;3) 找到性价比更高的标的;4) 估值显著高估。"),
                ExtractedNode.leaf("价值投资的四个基本条件与局限性",
                        "条件:有效市场失灵、自由现金流可估算、企业可分析、投资期限够长。局限:在牛市后期跑不赢风口,在情绪市可能持续跑输。")
        );
    }

    private static ExtractedNode cyclesAndLeverages() {
        return ExtractedNode.node(
                "四种周期与三种杠杆",
                "经济与资本市场存在四种周期:产能、库存、信贷、情绪;三种杠杆:经营、财务、估值。",
                "周期决定方向,杠杆决定弹性。",
                ExtractedNode.leaf("四种周期的演变顺序",
                        "情绪周期 → 信贷周期 → 库存周期 → 产能周期。前置周期决定后置周期的运行节奏。"),
                ExtractedNode.leaf("三种杠杆与板块轮动",
                        "估值杠杆驱动金融股,财务杠杆驱动地产/周期股,经营杠杆驱动消费/制造龙头。"),
                ExtractedNode.leaf("周期分析的应用与启示",
                        "在不同周期阶段配置不同杠杆敏感度的资产,可以平滑组合曲线、提高夏普比。")
        );
    }

    private static ExtractedNode tenYearOutlook() {
        return ExtractedNode.node(
                "未来10年投资路向",
                "在中国经济\"新常态\"下,寻找仍能跑出 10x 的领域。",
                "增速换挡,从总量为王进入结构为王。",
                ExtractedNode.leaf("中国经济的\"新常态\"",
                        "GDP 增速放缓、消费占比提升、服务业崛起、产业升级与高质量发展并行。"),
                ExtractedNode.leaf("\"新常态\"下的投资思路",
                        "聚焦优质龙头、低估值消费、品牌护城河、技术升级中的国产替代。"),
                ExtractedNode.leaf("\"新常态\"下的投资方向",
                        "大消费、医药健康、先进制造、半导体国产替代、新能源,以及具备出海能力的中国制造龙头。")
        );
    }

    private static ExtractedNode psychologyTraps() {
        return ExtractedNode.node(
                "投资者常见的心理误区",
                "行为金融视角:三类高发心理偏差,在熊牛切换时尤其致命。",
                "市场不是错的,错的是被偏差扭曲的我们。",
                ExtractedNode.leaf("过度自信与仓位思维",
                        "高估自身判断,忽视概率思维;\"all in\" 一只票或一个赛道,放大错误的代价。"),
                ExtractedNode.leaf("锚固偏见与短期趋势长期化",
                        "锚定历史成本或最近高点,把短期趋势外推成长期规律,在牛市顶点最容易犯。"),
                ExtractedNode.leaf("亏损厌恶症与羊群效应",
                        "对亏损的痛苦感是收益喜悦的 2 倍,促使人在底部割肉、在顶点追高;羊群效应放大这一过程。")
        );
    }

    private static ExtractedNode wisdom() {
        return ExtractedNode.node(
                "投资智慧与格言",
                "三句经典格言,精炼了价值投资的核心精神。",
                "经典之所以经典,因为反复奏效。",
                ExtractedNode.leaf("\"在别人恐惧时贪婪\"的真义",
                        "巴菲特名言。真正含义不是\"无脑抄底\",而是\"在被情绪杀价的好公司上加仓\"。"),
                ExtractedNode.leaf("\"留得青山在\"与风险区分",
                        "区分\"绝对损失\"和\"机会成本损失\";前者要竭力避免,后者必然存在。"),
                ExtractedNode.leaf("\"二鸟在林不如一鸟在手\"的启示",
                        "宁选确定的好公司,不去赌不确定的暴利标的——优势叠加比单点突破更可持续。")
        );
    }

    private static ExtractedNode treeWindHeart() {
        return ExtractedNode.node(
                "树动风动心动",
                "用三句话区分短中长期的股价驱动因素,帮助投资者建立时间维度感。",
                "股价 = 心动 × 风动 × 树动;期限越长,树动权重越大。",
                ExtractedNode.leaf("短期: 心在动 (情绪博弈)",
                        "短期股价由情绪与博弈主导,任何价格都可能合理或荒谬。"),
                ExtractedNode.leaf("中期: 风在动 (政策决定)",
                        "中期由政策、行业景气与资金面驱动,体现\"风口\"逻辑。"),
                ExtractedNode.leaf("长期: 树在动 (基本面决定)",
                        "长期最终回到企业基本面与现金流——树才是真正在长大的东西。")
        );
    }

    /**
     * 当 AI 不可用且文件标题与示例书不匹配时,提供一个通用 4 章模板。
     */
    public static ExtractedNode genericFallback(String title) {
        String safeTitle = title == null || title.isBlank() ? "未命名资料" : title;
        ExtractedNode root = ExtractedNode.node(
                "《" + safeTitle + "》核心知识体系",
                "基于您上传的资料自动整理出的知识框架草稿。后续可由 AI 补全或人工微调。",
                null
        );
        for (int i = 1; i <= 4; i++) {
            ExtractedNode chap = ExtractedNode.node(
                    "第 " + i + " 章 主题 " + i,
                    "本章总览与主要观点(占位)。",
                    null
            );
            for (int j = 1; j <= 3; j++) {
                chap.getChildren().add(ExtractedNode.leaf(
                        "知识点 " + i + "." + j,
                        "知识点 " + i + "." + j + " 的核心要点说明(占位)。"
                ));
            }
            root.getChildren().add(chap);
        }
        return root;
    }

    public static List<ExtractedNode> allBuiltIn() {
        return new ArrayList<>(List.of(investingSimpleThings()));
    }
}
