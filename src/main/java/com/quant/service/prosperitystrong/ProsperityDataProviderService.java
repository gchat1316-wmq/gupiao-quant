package com.quant.service.prosperitystrong;

import com.quant.config.ProsperityStrongProperties;
import com.quant.dto.prosperitystrong.ProviderCapabilityDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class ProsperityDataProviderService {

    private final ProsperityStrongProperties props;
    private final WindAifinMarketClient windClient;

    public String normalize(String provider) {
        String p = provider == null || provider.isBlank() ? props.getProvider() : provider;
        p = p == null || p.isBlank() ? "local" : p.trim().toLowerCase(Locale.ROOT);
        return switch (p) {
            case "wind", "tdx", "hybrid" -> p;
            default -> "local";
        };
    }

    public String providerMessage(String provider) {
        String p = normalize(provider);
        if ("local".equals(p)) {
            return "使用本地数据库/东方财富抓取链路执行";
        }
        if ("wind".equals(p)) {
            WindAifinMarketClient.WindCheck c = windClient.verify();
            return c.verified()
                    ? "Wind 链路已验证; 当前策略计算复用本地落库数据,Wind 可作为实时校验和补数入口"
                    : c.message();
        }
        if ("tdx".equals(p)) {
            ProviderCapabilityDTO tdx = tdxCapability();
            return tdx.getMessage();
        }
        WindAifinMarketClient.WindCheck c = windClient.verify();
        ProviderCapabilityDTO tdx = tdxCapability();
        return "混合链路: Wind=" + (c.verified() ? "已验证" : c.message())
                + "; 通达信=" + tdx.getMessage();
    }

    public List<ProviderCapabilityDTO> capabilities() {
        return List.of(localCapability(), windCapability(), tdxCapability(), hybridCapability());
    }

    private ProviderCapabilityDTO localCapability() {
        return ProviderCapabilityDTO.builder()
                .code("local")
                .label("本地/东方财富链路")
                .available(true)
                .verified(true)
                .role("当前生产链路")
                .message("板块抓取、成分股匹配、日线/财务硬筛均走本地库与现有抓取逻辑")
                .build();
    }

    private ProviderCapabilityDTO windCapability() {
        WindAifinMarketClient.WindCheck c = windClient.verify();
        return ProviderCapabilityDTO.builder()
                .code("wind")
                .label("Wind 链路")
                .available(c.installed() && windClient.hasApiKey())
                .verified(c.verified())
                .role("实时行情、自然语言筛选、后续补数")
                .message(c.message())
                .build();
    }

    private ProviderCapabilityDTO tdxCapability() {
        Path dir = Path.of(props.getTdx().getConnectorDir());
        boolean installed = Files.isDirectory(dir) && Files.isRegularFile(dir.resolve("mcp.json"));
        boolean directEnabled = props.getTdx().isEnabled();
        return ProviderCapabilityDTO.builder()
                .code("tdx")
                .label("通达信 MCP 链路")
                .available(installed)
                .verified(false)
                .role("行情、K线、问财/研报/公告等能力")
                .message(installed
                        ? (directEnabled
                        ? "connector 已发现; Java 直连 MCP 客户端待接入验证"
                        : "connector 已发现; 当前应用未启用 Java 直连 MCP,先作为可切换占位和能力诊断")
                        : "未发现通达信 connector 安装目录")
                .build();
    }

    private ProviderCapabilityDTO hybridCapability() {
        WindAifinMarketClient.WindCheck wind = windClient.verify();
        ProviderCapabilityDTO tdx = tdxCapability();
        boolean available = wind.verified() || tdx.isAvailable();
        return ProviderCapabilityDTO.builder()
                .code("hybrid")
                .label("Wind + 通达信混合链路")
                .available(available)
                .verified(wind.verified() && tdx.isVerified())
                .role("Wind 做实时校验/补数,通达信做行情与资讯增强")
                .message("Wind: " + wind.message() + "; 通达信: " + tdx.getMessage())
                .build();
    }
}
