package com.quant.prosperitystrong;

import com.quant.entity.ProsperityPickDaily;
import com.quant.entity.ProsperityStockPool;
import com.quant.repository.ProsperityPickDailyRepository;
import com.quant.repository.ProsperityStockPoolRepository;
import com.quant.service.prosperitystrong.ProsperityPoolService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ProsperityPoolService TDD 测试。
 *
 * RED phase: 先写期望行为 → 跑不过 → 修代码
 * 覆盖：
 * 1. promote(ownerId) 个人池：不同 owner 的同一股票互不影响
 * 2. promote(NULL) 系统池：共享数据
 * 3. list(ownerId) 返回个人池 + 系统池
 * 4. 重复入池：累加 poolCount
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProsperityPoolService")
class ProsperityPoolServiceTest {

    @Mock private ProsperityStockPoolRepository poolRepo;
    @Mock private ProsperityPickDailyRepository pickRepo;

    private ProsperityPoolService service;

    private static final LocalDate SNAP = LocalDate.of(2026, 6, 27);

    private ProsperityPickDaily makePick(String code, String name) {
        ProsperityPickDaily p = new ProsperityPickDaily();
        p.setStockCode(code);
        p.setStockName(name);
        p.setSnapDate(SNAP);
        p.setSectorName("半导体");
        p.setCombinedScore(new BigDecimal("85.5"));
        p.setLatestPrice(new BigDecimal("10.00"));
        p.setBuyLeftPrice(new BigDecimal("9.50"));
        p.setSellTarget1(new BigDecimal("12.00"));
        p.setStopLossPrice(new BigDecimal("8.50"));
        p.setCorePositionPct(new BigDecimal("30"));
        p.setTacticalPositionPct(new BigDecimal("20"));
        p.setActionSignal("add");
        return p;
    }

    @BeforeEach
    void setUp() {
        service = new ProsperityPoolService(poolRepo, pickRepo);
    }

    // ── promote(ownerId)：个人池隔离 ───────────────────

    @Nested
    @DisplayName("promote with ownerId")
    class PromoteWithOwnerId {

        @Test
        @DisplayName("ownerId=1 新股入池 → 创建个人池")
        void newStockForOwner1() {
            // snapDate 已传，findFirstByOrderBySnapDateDesc() 不会走到
            when(pickRepo.findBySnapDateAndStockCode(SNAP, "000001.SZ")).thenReturn(Optional.of(makePick("000001.SZ", "平安银行")));
            when(poolRepo.findByOwnerIdAndStockCode(1L, "000001.SZ")).thenReturn(Optional.empty());
            when(poolRepo.save(any(ProsperityStockPool.class)))
                    .thenAnswer(inv -> { ProsperityStockPool p = inv.getArgument(0); p.setId(10); return p; });

            service.promote("000001.SZ", SNAP, 1L);

            ArgumentCaptor<ProsperityStockPool> captor = ArgumentCaptor.forClass(ProsperityStockPool.class);
            verify(poolRepo).save(captor.capture());
            assertThat(captor.getValue().getOwnerId()).isEqualTo(1L);
            assertThat(captor.getValue().getPoolCount()).isEqualTo(1);
            assertThat(captor.getValue().getStockCode()).isEqualTo("000001.SZ");
        }

        @Test
        @DisplayName("ownerId=1 和 ownerId=2 的同一股票互不影响")
        void sameStockDifferentOwners() {
            // owner1 已有池
            ProsperityStockPool existingOwner1 = new ProsperityStockPool();
            existingOwner1.setId(1);
            existingOwner1.setOwnerId(1L);
            existingOwner1.setStockCode("000001.SZ");
            existingOwner1.setPoolCount(1);
            existingOwner1.setMemo("首次入池\n[2026-06-26] ...");

            when(pickRepo.findBySnapDateAndStockCode(SNAP, "000001.SZ")).thenReturn(Optional.of(makePick("000001.SZ", "平安银行")));
            when(poolRepo.findByOwnerIdAndStockCode(1L, "000001.SZ")).thenReturn(Optional.of(existingOwner1));
            when(poolRepo.findByOwnerIdAndStockCode(2L, "000001.SZ")).thenReturn(Optional.empty());
            when(poolRepo.save(any(ProsperityStockPool.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // owner1 再次入同一股票 → 累加 poolCount
            service.promote("000001.SZ", SNAP, 1L);

            // owner2 入同一股票 → 各自独立（new pool）
            service.promote("000001.SZ", SNAP, 2L);

            // 验证 save 共被调用 2 次
            ArgumentCaptor<ProsperityStockPool> captor = ArgumentCaptor.forClass(ProsperityStockPool.class);
            verify(poolRepo, times(2)).save(captor.capture());
            List<ProsperityStockPool> allSaved = captor.getAllValues();
            assertThat(allSaved).hasSize(2);

            // 第一次 save：owner1 的 existing 累加 poolCount=2
            assertThat(allSaved.get(0).getOwnerId()).isEqualTo(1L);
            assertThat(allSaved.get(0).getPoolCount()).isEqualTo(2);

            // 第二次 save：owner2 的新记录，poolCount=1
            assertThat(allSaved.get(1).getOwnerId()).isEqualTo(2L);
            assertThat(allSaved.get(1).getPoolCount()).isEqualTo(1);
        }
    }

    // ── promote(NULL)：系统共享池 ────────────────────

    @Nested
    @DisplayName("promote with NULL ownerId")
    class PromoteWithNullOwner {

        @Test
        @DisplayName("NULL ownerId → 系统共享池，findByOwnerIdIsNullAndStockCode")
        void nullOwnerCreatesSystemPool() {
            when(pickRepo.findBySnapDateAndStockCode(SNAP, "000001.SZ")).thenReturn(Optional.of(makePick("000001.SZ", "平安银行")));
            when(poolRepo.findByOwnerIdIsNullAndStockCode("000001.SZ")).thenReturn(Optional.empty());
            when(poolRepo.save(any(ProsperityStockPool.class)))
                    .thenAnswer(inv -> { ProsperityStockPool p = inv.getArgument(0); p.setId(20); return p; });

            service.promote("000001.SZ", SNAP, null);

            verify(poolRepo).findByOwnerIdIsNullAndStockCode("000001.SZ");
            ArgumentCaptor<ProsperityStockPool> captor = ArgumentCaptor.forClass(ProsperityStockPool.class);
            verify(poolRepo).save(captor.capture());
            assertThat(captor.getValue().getOwnerId()).isNull();
        }
    }

    // ── list(ownerId)：个人池 + 系统池 ───────────────

    @Nested
    @DisplayName("list with ownerId")
    class ListWithOwnerId {

        @Test
        @DisplayName("list(1L) → 个人池(user_id=1) + 系统池(NULL)")
        void listReturnsPersonalPlusSystem() {
            ProsperityStockPool personal = new ProsperityStockPool();
            personal.setId(1); personal.setOwnerId(1L); personal.setStockCode("000001.SZ");
            personal.setLastAddedAt(LocalDateTime.now());

            ProsperityStockPool system = new ProsperityStockPool();
            system.setId(2); system.setOwnerId(null); system.setStockCode("000002.SZ");
            system.setLastAddedAt(LocalDateTime.now());

            when(poolRepo.findByOwnerIdOrderByLastAddedAtDesc(1L)).thenReturn(List.of(personal));
            when(poolRepo.findByOwnerIdIsNullOrderByLastAddedAtDesc()).thenReturn(List.of(system));

            List<ProsperityStockPool> result = service.list(1L);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getOwnerId()).isEqualTo(1L);  // 个人在前
            assertThat(result.get(1).getOwnerId()).isNull();         // 系统在后
        }

        @Test
        @DisplayName("list() 无参数 → 返回全部（兼容旧调用）")
        void listAllReturnsEverything() {
            when(poolRepo.findAllByOrderByLastAddedAtDesc()).thenReturn(List.of());

            service.list();

            verify(poolRepo).findAllByOrderByLastAddedAtDesc();
        }
    }
}
