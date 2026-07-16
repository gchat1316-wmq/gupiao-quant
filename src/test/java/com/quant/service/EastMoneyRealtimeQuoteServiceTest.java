package com.quant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import com.quant.entity.TechAiQuoteSnapshot;

/**
 * 大阳线实时行情拉取服务的并发/降级行为测试。
 *
 * <p>这里不验证网络往返耗时（那需要集成测试或 staging），只验证：
 *
 * <ol>
 *   <li>N 个 code 全部被并发请求（不串行阻塞）
 *   <li>主域失败 → 切备用域；备用域成功 → 返回结果
 *   <li>主备都失败 → 返 null（不抛异常）
 * </ol>
 *
 * <p>用真实 QuoteHttpClient + Mockito.spy：executor() 真实并发，getUtf8 被 stub 拦截。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EastMoneyRealtimeQuoteService")
class EastMoneyRealtimeQuoteServiceTest {

  private QuoteHttpClient quoteHttpClient;
  private EastMoneyRealtimeQuoteService service;

  @BeforeEach
  void setUp() {
    // 真实例：executor() 跑真实并发 (8 线程)
    QuoteHttpClient real = new QuoteHttpClient(2000, 3000, 8);
    // spy：executor() 不 stub 走真实实现，getUtf8 走 mock 拦截
    quoteHttpClient = spy(real);
    service = new EastMoneyRealtimeQuoteService(quoteHttpClient);
  }

  @Test
  @DisplayName("空输入直接返空 Map，不发任何请求")
  void fetchEmptyReturnsEmpty() {
    assertThat(service.fetch(List.of())).isEmpty();
    assertThat(service.fetch((Collection<String>) null)).isEmpty();
  }

  @Test
  @DisplayName("50 只股票并发拉取，请求次数 = 50 且总耗时远小于串行")
  void fetchConcurrently() {
    AtomicInteger callCount = new AtomicInteger();
    doAnswer(
            inv -> {
              callCount.incrementAndGet();
              try {
                Thread.sleep(50);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              String url = inv.getArgument(0);
              String secid = url.substring(url.indexOf("secid=") + "secid=".length());
              String code = secid.substring(2) + "." + (secid.startsWith("1.") ? "SH" : "SZ");
              return "{\"rc\":0,\"data\":{\"f43\":1000,\"f57\":\""
                  + code
                  + "\",\"f86\":1700000000}}";
            })
        .when(quoteHttpClient)
        .getUtf8(anyString());

    List<String> codes = new ArrayList<>();
    for (int i = 0; i < 50; i++) codes.add(String.format("%06d.SZ", 600000 + i));

    long start = System.nanoTime();
    Map<String, TechAiQuoteSnapshot> result = service.fetch(codes);
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertThat(callCount.get()).isEqualTo(50);
    assertThat(result).hasSize(50);
    // 50ms × 50 = 2500ms 串行；8 线程并发 < 600ms。给宽限：< 1500ms
    assertThat(elapsedMs).as("并发执行应远快于串行 (50ms × 50 / 8 = ~312ms)").isLessThan(1500);
  }

  @Test
  @DisplayName("主域失败 → 备用域：备用域返有效 JSON 时取备用域结果")
  void fetchFallsBackToBackupHost() {
    String json = "{\"rc\":0,\"data\":{\"f43\":1050,\"f57\":\"600000.SH\",\"f86\":1700000000}}";
    doReturn(null)
        .when(quoteHttpClient)
        .getUtf8(org.mockito.ArgumentMatchers.contains("push2.eastmoney.com"));
    doReturn(json)
        .when(quoteHttpClient)
        .getUtf8(org.mockito.ArgumentMatchers.contains("push2delay.eastmoney.com"));

    Map<String, TechAiQuoteSnapshot> result = service.fetch(List.of("600000.SH"));

    assertThat(result).hasSize(1);
    assertThat(result.get("600000.sh")).isNotNull();
    assertThat(result.get("600000.sh").getLatestPrice()).isEqualByComparingTo("10.50");
  }

  @Test
  @DisplayName("主备都失败 → 返空 Map 不抛异常")
  void fetchAllFailReturnsEmpty() {
    doReturn(null).when(quoteHttpClient).getUtf8(anyString());

    Map<String, TechAiQuoteSnapshot> result = service.fetch(List.of("600000.SH", "000001.SZ"));

    assertThat(result).isEmpty();
  }
}
