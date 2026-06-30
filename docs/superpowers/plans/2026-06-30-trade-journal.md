# Trade Journal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a trade journal module (`/gp/journal.html` + `/api/journal/*`) that lets the user record every trade (real or paper), persist setup/review notes, auto-calculate R-multiple/EV/drawdown, and visualize equity curve + R distribution — supporting the "learn → practice → review" loop the user explicitly asked for.

**Architecture:** New Spring Boot module under `com.quant.journal` (controller / service / repository / entity / dto). Single `journal_trade` table holds both real and paper trades (distinguished by `mode`). Stats computed on-the-fly in `JournalStatsService`. Cron-driven refresh of open trades + auto-close on target hit with Server酱 push. Frontend is a vanilla HTML/CSS/JS three-column page that reuses the existing `fetchCurrentPrice` and Chart.js CDN.

**Tech Stack:** Java 17, Spring Boot 3.2.5, Spring Data JPA + Hibernate, MySQL 8, JUnit 5 + Mockito, vanilla HTML/JS/CSS, Chart.js 4 (CDN).

**Spec:** `docs/superpowers/specs/2026-06-30-trade-journal-design.md`

---

## File Structure

### Backend — new files
- `sql/journal_init.sql` — schema
- `src/main/java/com/quant/entity/JournalTrade.java` — JPA entity
- `src/main/java/com/quant/repository/JournalTradeRepository.java` — Spring Data
- `src/main/java/com/quant/dto/journal/JournalTradeDTO.java`
- `src/main/java/com/quant/dto/journal/JournalTradeCreateRequest.java`
- `src/main/java/com/quant/dto/journal/JournalTradeUpdateRequest.java`
- `src/main/java/com/quant/dto/journal/JournalStatsDTO.java`
- `src/main/java/com/quant/dto/journal/EquityCurvePoint.java`
- `src/main/java/com/quant/dto/journal/RDistributionBucket.java`
- `src/main/java/com/quant/dto/journal/PendingFillDTO.java` — for POOL_SYNC
- `src/main/java/com/quant/service/journal/JournalService.java`
- `src/main/java/com/quant/service/journal/JournalStatsService.java`
- `src/main/java/com/quant/service/journal/JournalCronService.java`
- `src/main/java/com/quant/controller/JournalController.java`

### Backend — modify
- `src/main/java/com/quant/config/SchemaInitializer.java` — add `ensureJournalTables()`
- `src/main/java/com/quant/security/SecurityConfig.java` — protect `/api/journal/**` with `.authenticated()`
- `src/main/resources/application.yml` — add `journal.refresh-cron`

### Frontend — new files
- `src/main/resources/static/journal.html`
- `src/main/resources/static/js/journal.js`
- `src/main/resources/static/css/journal.css`

### Frontend — modify
- `src/main/resources/static/header.html` — add 复盘 nav link

### Tests — new files
- `src/test/java/com/quant/service/journal/JournalStatsServiceTest.java`
- `src/test/java/com/quant/service/journal/JournalServiceTest.java`
- `src/test/java/com/quant/controller/JournalControllerTest.java`
- `src/test/java/com/quant/service/journal/JournalCronServiceTest.java`

---

## Task 1: Schema — `journal_trade` table + `SchemaInitializer`

**Files:**
- Create: `sql/journal_init.sql`
- Modify: `src/main/java/com/quant/config/SchemaInitializer.java`

- [ ] **Step 1: Create `sql/journal_init.sql`**

```sql
-- ============================================================
-- Trade Journal (journal_trade) — 2026-06-30
-- ============================================================

CREATE TABLE IF NOT EXISTS journal_trade (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  mode VARCHAR(10) NOT NULL,
  stock_code VARCHAR(20) NOT NULL,
  stock_name VARCHAR(50),

  entry_price DECIMAL(10,2) NOT NULL,
  entry_date DATETIME NOT NULL,
  entry_shares INT NOT NULL,
  account_at_entry DECIMAL(14,2),
  risk_percent DECIMAL(5,4),
  stop_price DECIMAL(10,2) NOT NULL,
  target_price DECIMAL(10,2),

  exit_price DECIMAL(10,2),
  exit_date DATETIME,
  exit_reason VARCHAR(30),
  initial_risk DECIMAL(10,2) NOT NULL,

  pnl_amount DECIMAL(14,2),
  r_multiple DECIMAL(8,4),
  is_open TINYINT DEFAULT 1,

  tags VARCHAR(200),
  setup_notes TEXT,
  review_notes TEXT,

  source VARCHAR(20),
  source_ref_id BIGINT,

  is_deleted TINYINT DEFAULT 0,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  INDEX idx_mode_open (mode, is_open),
  INDEX idx_stock (stock_code),
  INDEX idx_exit_date (exit_date),
  UNIQUE KEY uk_source_ref (source, source_ref_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- [ ] **Step 2: Add `ensureJournalTables()` to `SchemaInitializer`**

Open `src/main/java/com/quant/config/SchemaInitializer.java`, add this method (place it after `ensureInvestTables()` — find the existing pattern; each `ensureXxx()` is a separate method called from the run() method's chain):

```java
private void ensureJournalTables() {
    try {
        jdbc.execute("CREATE TABLE IF NOT EXISTS journal_trade ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "mode VARCHAR(10) NOT NULL,"
                + "stock_code VARCHAR(20) NOT NULL,"
                + "stock_name VARCHAR(50),"
                + "entry_price DECIMAL(10,2) NOT NULL,"
                + "entry_date DATETIME NOT NULL,"
                + "entry_shares INT NOT NULL,"
                + "account_at_entry DECIMAL(14,2),"
                + "risk_percent DECIMAL(5,4),"
                + "stop_price DECIMAL(10,2) NOT NULL,"
                + "target_price DECIMAL(10,2),"
                + "exit_price DECIMAL(10,2),"
                + "exit_date DATETIME,"
                + "exit_reason VARCHAR(30),"
                + "initial_risk DECIMAL(10,2) NOT NULL,"
                + "pnl_amount DECIMAL(14,2),"
                + "r_multiple DECIMAL(8,4),"
                + "is_open TINYINT DEFAULT 1,"
                + "tags VARCHAR(200),"
                + "setup_notes TEXT,"
                + "review_notes TEXT,"
                + "source VARCHAR(20),"
                + "source_ref_id BIGINT,"
                + "is_deleted TINYINT DEFAULT 0,"
                + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP,"
                + "updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                + "INDEX idx_mode_open (mode, is_open),"
                + "INDEX idx_stock (stock_code),"
                + "INDEX idx_exit_date (exit_date),"
                + "UNIQUE KEY uk_source_ref (source, source_ref_id)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    } catch (Exception e) {
        log.warn("[Schema] ensureJournalTables skipped: {}", e.getMessage());
    }
}
```

Then add `ensureJournalTables();` to the `run()` method's chain (right after the existing `ensureXxx()` calls in order). Look at how the other tables are called in `run()` to match the pattern.

- [ ] **Step 3: Build and verify**

```bash
mvn compile -q
```

Expected: BUILD SUCCESS, no compile errors.

- [ ] **Step 4: Run app briefly to verify auto-create**

```bash
mvn spring-boot:run &
sleep 25
mysql -u<user> -p wucai_trade -e "SHOW CREATE TABLE journal_trade\G" | head -30
kill %1
```

Expected: Table exists with all columns from the spec.

- [ ] **Step 5: Commit**

```bash
git add sql/journal_init.sql src/main/java/com/quant/config/SchemaInitializer.java
git commit -m "feat(journal): schema + SchemaInitializer.ensureJournalTables"
```

---

## Task 2: JPA Entity `JournalTrade`

**Files:**
- Create: `src/main/java/com/quant/entity/JournalTrade.java`

- [ ] **Step 1: Write failing test for entity mapping**

Create `src/test/java/com/quant/entity/JournalTradeMappingTest.java`:

```java
package com.quant.entity;

import org.junit.jupiter.api.Test;
import jakarta.persistence.Persistence;

import static org.junit.jupiter.api.Assertions.*;

class JournalTradeMappingTest {

    @Test
    void entityLoadsInPersistenceUnit() {
        var emf = Persistence.createEntityManagerFactory("test-unit");
        try (var em = emf.createEntityManager()) {
            assertNotNull(em.getMetamodel().entity(JournalTrade.class));
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails (no entity yet)**

```bash
mvn test -Dtest=JournalTradeMappingTest -q 2>&1 | tail -10
```

Expected: Compilation failure — `JournalTrade` does not exist.

- [ ] **Step 3: Create entity `JournalTrade.java`**

```java
package com.quant.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "journal_trade")
public class JournalTrade {

    public enum Mode { REAL, PAPER }
    public enum ExitReason { stopped_out, target_hit, manual, time_stop, system_stop }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 10)
    private Mode mode;

    @Column(name = "stock_code", nullable = false, length = 20)
    private String stockCode;

    @Column(name = "stock_name", length = 50)
    private String stockName;

    @Column(name = "entry_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal entryPrice;

    @Column(name = "entry_date", nullable = false)
    private LocalDateTime entryDate;

    @Column(name = "entry_shares", nullable = false)
    private Integer entryShares;

    @Column(name = "account_at_entry", precision = 14, scale = 2)
    private BigDecimal accountAtEntry;

    @Column(name = "risk_percent", precision = 5, scale = 4)
    private BigDecimal riskPercent;

    @Column(name = "stop_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal stopPrice;

    @Column(name = "target_price", precision = 10, scale = 2)
    private BigDecimal targetPrice;

    @Column(name = "exit_price", precision = 10, scale = 2)
    private BigDecimal exitPrice;

    @Column(name = "exit_date")
    private LocalDateTime exitDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "exit_reason", length = 30)
    private ExitReason exitReason;

    @Column(name = "initial_risk", nullable = false, precision = 10, scale = 2)
    private BigDecimal initialRisk;

    @Column(name = "pnl_amount", precision = 14, scale = 2)
    private BigDecimal pnlAmount;

    @Column(name = "r_multiple", precision = 8, scale = 4)
    private BigDecimal rMultiple;

    @Column(name = "is_open")
    private Integer isOpen = 1;

    @Column(name = "tags", length = 200)
    private String tags;

    @Column(name = "setup_notes", columnDefinition = "TEXT")
    private String setupNotes;

    @Column(name = "review_notes", columnDefinition = "TEXT")
    private String reviewNotes;

    @Column(name = "source", length = 20)
    private String source;

    @Column(name = "source_ref_id")
    private Long sourceRefId;

    @Column(name = "is_deleted")
    private Integer isDeleted = 0;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
mvn test -Dtest=JournalTradeMappingTest -q 2>&1 | tail -10
```

Expected: PASS (or compilation warning about test-unit persistence; if test-unit not defined, simplify the test to just check field mappings exist via reflection — replace with the simpler variant below).

If `test-unit` PU is not configured, replace the test body with:

```java
package com.quant.entity;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import static org.junit.jupiter.api.Assertions.*;

class JournalTradeMappingTest {

    @Test
    void hasAllMappedFields() throws Exception {
        var expected = java.util.Set.of(
            "id","mode","stockCode","stockName","entryPrice","entryDate","entryShares",
            "accountAtEntry","riskPercent","stopPrice","targetPrice","exitPrice",
            "exitDate","exitReason","initialRisk","pnlAmount","rMultiple","isOpen",
            "tags","setupNotes","reviewNotes","source","sourceRefId","isDeleted",
            "createdAt","updatedAt");
        var actual = new java.util.HashSet<String>();
        for (Field f : JournalTrade.class.getDeclaredFields()) actual.add(f.getName());
        assertEquals(expected, actual);
    }
}
```

Run again — expected PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/quant/entity/JournalTrade.java src/test/java/com/quant/entity/JournalTradeMappingTest.java
git commit -m "feat(journal): JournalTrade entity + mapping test"
```

---

## Task 3: Repository

**Files:**
- Create: `src/main/java/com/quant/repository/JournalTradeRepository.java`

- [ ] **Step 1: Create repository**

```java
package com.quant.repository;

import com.quant.entity.JournalTrade;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface JournalTradeRepository extends JpaRepository<JournalTrade, Long> {

    /** Soft-deletion aware base query — never returns is_deleted=1 */
    @Query("SELECT j FROM JournalTrade j WHERE j.isDeleted = 0")
    Page<JournalTrade> findAllActive(Pageable pageable);

    @Query("SELECT j FROM JournalTrade j WHERE j.id = :id AND j.isDeleted = 0")
    Optional<JournalTrade> findActiveById(@Param("id") Long id);

    @Query("SELECT j FROM JournalTrade j WHERE j.isDeleted = 0 AND j.isOpen = 1 ORDER BY j.entryDate DESC")
    List<JournalTrade> findAllOpen();

    @Query("SELECT j FROM JournalTrade j WHERE j.isDeleted = 0 AND j.isOpen = 0 ORDER BY j.exitDate DESC")
    List<JournalTrade> findAllClosed();

    @Query("SELECT j FROM JournalTrade j WHERE j.isDeleted = 0 AND j.mode = :mode AND j.isOpen = 0 ORDER BY j.exitDate ASC")
    List<JournalTrade> findClosedByMode(@Param("mode") JournalTrade.Mode mode);

    @Query("SELECT j FROM JournalTrade j WHERE j.isDeleted = 0 AND j.isOpen = 0 ORDER BY j.exitDate ASC")
    List<JournalTrade> findAllClosedOrdered();

    @Query("SELECT j FROM JournalTrade j WHERE j.source = 'POOL_SYNC' AND j.sourceRefId = :refId")
    Optional<JournalTrade> findBySourceRef(@Param("refId") Long refId);
}
```

- [ ] **Step 2: Verify it compiles**

```bash
mvn compile -q 2>&1 | tail -10
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/quant/repository/JournalTradeRepository.java
git commit -m "feat(journal): JournalTradeRepository with active/open/closed queries"
```

---

## Task 4: DTOs

**Files:**
- Create: `src/main/java/com/quant/dto/journal/JournalTradeDTO.java`
- Create: `src/main/java/com/quant/dto/journal/JournalTradeCreateRequest.java`
- Create: `src/main/java/com/quant/dto/journal/JournalTradeUpdateRequest.java`
- Create: `src/main/java/com/quant/dto/journal/JournalStatsDTO.java`
- Create: `src/main/java/com/quant/dto/journal/EquityCurvePoint.java`
- Create: `src/main/java/com/quant/dto/journal/RDistributionBucket.java`
- Create: `src/main/java/com/quant/dto/journal/PendingFillDTO.java`

- [ ] **Step 1: Create `JournalTradeDTO.java`**

```java
package com.quant.dto.journal;

import com.quant.entity.JournalTrade;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Builder
public class JournalTradeDTO {
    private Long id;
    private String mode;
    private String stockCode;
    private String stockName;
    private BigDecimal entryPrice;
    private LocalDateTime entryDate;
    private Integer entryShares;
    private BigDecimal accountAtEntry;
    private BigDecimal riskPercent;
    private BigDecimal stopPrice;
    private BigDecimal targetPrice;
    private BigDecimal exitPrice;
    private LocalDateTime exitDate;
    private String exitReason;
    private BigDecimal initialRisk;
    private BigDecimal pnlAmount;
    private BigDecimal rMultiple;
    private Boolean isOpen;
    private String tags;
    private String setupNotes;
    private String reviewNotes;
    private String source;
    private Long sourceRefId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static JournalTradeDTO from(JournalTrade j) {
        if (j == null) return null;
        return JournalTradeDTO.builder()
                .id(j.getId())
                .mode(j.getMode() != null ? j.getMode().name() : null)
                .stockCode(j.getStockCode())
                .stockName(j.getStockName())
                .entryPrice(j.getEntryPrice())
                .entryDate(j.getEntryDate())
                .entryShares(j.getEntryShares())
                .accountAtEntry(j.getAccountAtEntry())
                .riskPercent(j.getRiskPercent())
                .stopPrice(j.getStopPrice())
                .targetPrice(j.getTargetPrice())
                .exitPrice(j.getExitPrice())
                .exitDate(j.getExitDate())
                .exitReason(j.getExitReason() != null ? j.getExitReason().name() : null)
                .initialRisk(j.getInitialRisk())
                .pnlAmount(j.getPnlAmount())
                .rMultiple(j.getRMultiple())
                .isOpen(j.getIsOpen() != null && j.getIsOpen() == 1)
                .tags(j.getTags())
                .setupNotes(j.getSetupNotes())
                .reviewNotes(j.getReviewNotes())
                .source(j.getSource())
                .sourceRefId(j.getSourceRefId())
                .createdAt(j.getCreatedAt())
                .updatedAt(j.getUpdatedAt())
                .build();
    }
}
```

- [ ] **Step 2: Create `JournalTradeCreateRequest.java`**

```java
package com.quant.dto.journal;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
public class JournalTradeCreateRequest {
    /** Required: REAL or PAPER */
    private String mode;
    /** Required */
    private String stockCode;
    private String stockName;
    /** Required */
    private BigDecimal entryPrice;
    /** Defaults to now() if null */
    private LocalDateTime entryDate;
    /** Required: shares (multiple of 100) */
    private Integer entryShares;
    private BigDecimal accountAtEntry;
    /** Optional: 0.01 = 1%. If null, computed from accountAtEntry + initial_risk */
    private BigDecimal riskPercent;
    /** Required */
    private BigDecimal stopPrice;
    private BigDecimal targetPrice;
    private String tags;
    private String setupNotes;
}
```

- [ ] **Step 3: Create `JournalTradeUpdateRequest.java`**

```java
package com.quant.dto.journal;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
public class JournalTradeUpdateRequest {
    /** When set, closes the trade. */
    private BigDecimal exitPrice;
    private LocalDateTime exitDate;
    /** stopped_out / target_hit / manual / time_stop / system_stop */
    private String exitReason;
    private BigDecimal stopPrice;
    private BigDecimal targetPrice;
    private String tags;
    private String setupNotes;
    private String reviewNotes;
}
```

- [ ] **Step 4: Create `JournalStatsDTO.java`**

```java
package com.quant.dto.journal;

import lombok.Builder;
import lombok.Getter;
import java.math.BigDecimal;

@Getter
@Builder
public class JournalStatsDTO {
    private Integer totalTrades;       // closed count
    private Integer wins;
    private Integer losses;
    private BigDecimal winRate;        // 0.42 = 42%
    private BigDecimal averageR;       // mean of r_multiple
    private BigDecimal averageWinR;    // mean of winning r_multiple
    private BigDecimal averageLossR;   // mean of losing r_multiple (negative)
    private BigDecimal expectedValue;  // win_rate * avg_win + loss_rate * avg_loss
    private BigDecimal maxDrawdown;    // in R units (negative)
    private Long longestWinStreak;
    private Long longestLossStreak;
}
```

- [ ] **Step 5: Create `EquityCurvePoint.java`**

```java
package com.quant.dto.journal;

import lombok.AllArgsConstructor;
import lombok.Getter;
import java.math.BigDecimal;

@Getter
@AllArgsConstructor
public class EquityCurvePoint {
    private Integer tradeIndex;       // 1-based ordinal of closed trade
    private Long tradeId;
    private String exitDate;          // ISO local date
    private BigDecimal cumulativeR;
}
```

- [ ] **Step 6: Create `RDistributionBucket.java`**

```java
package com.quant.dto.journal;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class RDistributionBucket {
    private String label;
    private Long count;
}
```

- [ ] **Step 7: Create `PendingFillDTO.java`**

```java
package com.quant.dto.journal;

import lombok.Builder;
import lombok.Getter;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Builder
public class PendingFillDTO {
    private Long fillId;
    private String poolType;          // invest_stock_pool / tech_ai_pool / potential_pool
    private String stockCode;
    private String stockName;
    private String action;            // open / add / reduce / clear
    private BigDecimal price;
    private BigDecimal lots;
    private LocalDateTime filledAt;
    private String note;
}
```

- [ ] **Step 8: Compile**

```bash
mvn compile -q 2>&1 | tail -10
```

Expected: BUILD SUCCESS.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/quant/dto/journal/
git commit -m "feat(journal): request/response DTOs"
```

---

## Task 5: `JournalService` — create with validations

**Files:**
- Create: `src/main/java/com/quant/service/journal/JournalService.java`
- Create: `src/test/java/com/quant/service/journal/JournalServiceCreateTest.java`

- [ ] **Step 1: Write failing test for create-with-validation**

```java
package com.quant.service.journal;

import com.quant.dto.journal.JournalTradeCreateRequest;
import com.quant.entity.JournalTrade;
import com.quant.repository.JournalTradeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JournalServiceCreateTest {

    @Mock JournalTradeRepository repo;
    @InjectMocks JournalService service;

    @Test
    void create_validatesRiskRewardRatio() {
        var req = new JournalTradeCreateRequest();
        req.setMode("REAL");
        req.setStockCode("600519");
        req.setEntryPrice(new BigDecimal("100"));
        req.setStopPrice(new BigDecimal("98"));
        req.setTargetPrice(new BigDecimal("101"));   // R:R = 1:0.5 (too low)
        req.setEntryShares(100);

        var ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(req, "user-1"));
        assertTrue(ex.getMessage().contains("1:3"));
        verifyNoInteractions(repo);
    }

    @Test
    void create_validatesRiskPercent() {
        var req = new JournalTradeCreateRequest();
        req.setMode("REAL");
        req.setStockCode("600519");
        req.setEntryPrice(new BigDecimal("100"));
        req.setStopPrice(new BigDecimal("95"));
        req.setTargetPrice(new BigDecimal("115"));
        req.setEntryShares(100);
        req.setRiskPercent(new BigDecimal("0.05"));   // 5% > 2% hard limit

        var ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(req, "user-1"));
        assertTrue(ex.getMessage().contains("2%"));
        verifyNoInteractions(repo);
    }

    @Test
    void create_computesInitialRiskAndPersists() {
        when(repo.save(any(JournalTrade.class))).thenAnswer(inv -> {
            JournalTrade j = inv.getArgument(0);
            j.setId(42L);
            return j;
        });

        var req = new JournalTradeCreateRequest();
        req.setMode("PAPER");
        req.setStockCode("002415");
        req.setStockName("海康");
        req.setEntryPrice(new BigDecimal("35"));
        req.setStopPrice(new BigDecimal("33"));
        req.setTargetPrice(new BigDecimal("41"));      // R:R = 6:2 = 3:1, OK
        req.setEntryShares(500);

        var dto = service.create(req, "user-1");

        ArgumentCaptor<JournalTrade> cap = ArgumentCaptor.forClass(JournalTrade.class);
        verify(repo).save(cap.capture());
        var saved = cap.getValue();
        assertEquals(new BigDecimal("2.00"), saved.getInitialRisk()); // 35 - 33
        assertEquals(JournalTrade.Mode.PAPER, saved.getMode());
        assertEquals("user-1", saved.getCreatedBy());
        assertEquals(1, saved.getIsOpen());
        assertEquals(dto.getInitialRisk(), saved.getInitialRisk());
    }
}
```

- [ ] **Step 2: Run test to verify it fails (no service yet)**

```bash
mvn test -Dtest=JournalServiceCreateTest -q 2>&1 | tail -15
```

Expected: Compilation failure — `JournalService` does not exist.

- [ ] **Step 3: Implement `JournalService.create()`**

```java
package com.quant.service.journal;

import com.quant.dto.journal.*;
import com.quant.entity.JournalTrade;
import com.quant.repository.JournalTradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class JournalService {

    private final JournalTradeRepository repo;

    @Transactional
    public JournalTradeDTO create(JournalTradeCreateRequest req, String username) {
        validate(req);
        JournalTrade j = new JournalTrade();
        j.setMode(JournalTrade.Mode.valueOf(req.getMode()));
        j.setStockCode(req.getStockCode());
        j.setStockName(req.getStockName());
        j.setEntryPrice(req.getEntryPrice());
        j.setEntryDate(req.getEntryDate() != null ? req.getEntryDate() : LocalDateTime.now());
        j.setEntryShares(req.getEntryShares());
        j.setAccountAtEntry(req.getAccountAtEntry());
        j.setRiskPercent(req.getRiskPercent());
        j.setStopPrice(req.getStopPrice());
        j.setTargetPrice(req.getTargetPrice());
        j.setInitialRisk(req.getEntryPrice().subtract(req.getStopPrice())
                .setScale(2, RoundingMode.HALF_UP));
        j.setIsOpen(1);
        j.setTags(req.getTags());
        j.setSetupNotes(req.getSetupNotes());
        j.setSource("MANUAL");
        j.setCreatedBy(username);  // See Task 5b for the createdBy column
        return JournalTradeDTO.from(repo.save(j));
    }

    private void validate(JournalTradeCreateRequest req) {
        if (req.getMode() == null
                || (!req.getMode().equals("REAL") && !req.getMode().equals("PAPER"))) {
            throw new IllegalArgumentException("mode 必须为 REAL 或 PAPER");
        }
        if (req.getStockCode() == null || req.getStockCode().isBlank()) {
            throw new IllegalArgumentException("stockCode 必填");
        }
        if (req.getEntryPrice() == null || req.getEntryPrice().signum() <= 0) {
            throw new IllegalArgumentException("entryPrice 必须 > 0");
        }
        if (req.getStopPrice() == null || req.getStopPrice().signum() <= 0) {
            throw new IllegalArgumentException("stopPrice 必须 > 0");
        }
        if (req.getEntryPrice().compareTo(req.getStopPrice()) <= 0) {
            throw new IllegalArgumentException("entryPrice 必须 > stopPrice");
        }
        if (req.getEntryShares() == null || req.getEntryShares() <= 0) {
            throw new IllegalArgumentException("entryShares 必须 > 0");
        }
        if (req.getTargetPrice() != null) {
            BigDecimal reward = req.getTargetPrice().subtract(req.getEntryPrice());
            BigDecimal risk = req.getEntryPrice().subtract(req.getStopPrice());
            if (reward.compareTo(risk.multiply(new BigDecimal("3"))) < 0) {
                throw new IllegalArgumentException(
                        "风险回报比 < 1:3,违反红线 — Minervini 不会进场");
            }
        }
        if (req.getRiskPercent() != null
                && req.getRiskPercent().compareTo(new BigDecimal("0.02")) > 0) {
            throw new IllegalArgumentException(
                    "单笔风险 " + req.getRiskPercent().multiply(new BigDecimal("100"))
                            + "% 超过 2% 红线");
        }
    }
}
```

- [ ] **Step 4: Add `created_by` column to entity + schema**

First update `sql/journal_init.sql` (add `created_by VARCHAR(50)` after `source_ref_id`) and re-run ensureJournalTables. To keep schema in sync, modify `SchemaInitializer.ensureJournalTables()` to also add:

```java
jdbc.execute("ALTER TABLE journal_trade ADD COLUMN IF NOT EXISTS created_by VARCHAR(50)");
```

(wrap in try/catch with log.warn like the others.)

Add the same column to `JournalTrade.java`:

```java
@Column(name = "created_by", length = 50)
private String createdBy;
```

- [ ] **Step 5: Run tests — they should pass now**

```bash
mvn test -Dtest=JournalServiceCreateTest -q 2>&1 | tail -15
```

Expected: 3 tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/quant/service/journal/JournalService.java \
        src/main/java/com/quant/entity/JournalTrade.java \
        src/main/java/com/quant/config/SchemaInitializer.java \
        sql/journal_init.sql \
        src/test/java/com/quant/service/journal/JournalServiceCreateTest.java
git commit -m "feat(journal): JournalService.create with R:R + risk% hard checks"
```

---

## Task 6: `JournalService` — close / update trade

**Files:**
- Modify: `src/main/java/com/quant/service/journal/JournalService.java`
- Create: `src/test/java/com/quant/service/journal/JournalServiceCloseTest.java`

- [ ] **Step 1: Write failing test for close-trade logic**

```java
package com.quant.service.journal;

import com.quant.dto.journal.JournalTradeUpdateRequest;
import com.quant.entity.JournalTrade;
import com.quant.repository.JournalTradeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JournalServiceCloseTest {

    @Mock JournalTradeRepository repo;
    @InjectMocks JournalService service;

    @Test
    void close_computesRMultipleAndPnl() {
        var existing = new JournalTrade();
        existing.setId(1L);
        existing.setMode(JournalTrade.Mode.REAL);
        existing.setEntryPrice(new BigDecimal("100"));
        existing.setStopPrice(new BigDecimal("95"));
        existing.setInitialRisk(new BigDecimal("5.00"));
        existing.setEntryShares(200);
        existing.setIsOpen(1);
        when(repo.findActiveById(1L)).thenReturn(Optional.of(existing));
        when(repo.save(any(JournalTrade.class))).thenAnswer(inv -> inv.getArgument(0));

        var req = new JournalTradeUpdateRequest();
        req.setExitPrice(new BigDecimal("115"));
        req.setExitReason("manual");
        req.setExitDate(LocalDateTime.of(2026, 6, 30, 15, 0));

        var dto = service.update(1L, req);

        // pnl = (115-100)*200 = 3000
        // r = 3000 / (5 * 200) = 3.0
        ArgumentCaptor<JournalTrade> cap = ArgumentCaptor.forClass(JournalTrade.class);
        verify(repo).save(cap.capture());
        var saved = cap.getValue();
        assertEquals(new BigDecimal("3000.00"), saved.getPnlAmount());
        assertEquals(0, saved.getRMultiple().compareTo(new BigDecimal("3.0000")));
        assertEquals(0, saved.getIsOpen());
        assertEquals(JournalTrade.ExitReason.manual, saved.getExitReason());
        assertNotNull(dto.getExitPrice());
    }

    @Test
    void update_stopLossOnly_keepsTradeOpen() {
        var existing = new JournalTrade();
        existing.setId(1L);
        existing.setEntryPrice(new BigDecimal("100"));
        existing.setStopPrice(new BigDecimal("95"));
        existing.setInitialRisk(new BigDecimal("5.00"));
        existing.setEntryShares(100);
        existing.setIsOpen(1);
        when(repo.findActiveById(1L)).thenReturn(Optional.of(existing));
        when(repo.save(any(JournalTrade.class))).thenAnswer(inv -> inv.getArgument(0));

        var req = new JournalTradeUpdateRequest();
        req.setStopPrice(new BigDecimal("97"));   // tighten only
        req.setTags("海龟,练习1");

        var dto = service.update(1L, req);

        assertNull(dto.getExitPrice());
        assertEquals(1, dto.getIsOpen() ? 1 : 0);
        ArgumentCaptor<JournalTrade> cap = ArgumentCaptor.forClass(JournalTrade.class);
        verify(repo).save(cap.capture());
        assertEquals(new BigDecimal("97"), cap.getValue().getStopPrice());
        assertEquals("海龟,练习1", cap.getValue().getTags());
    }

    @Test
    void update_relaxesStopLoss_throws() {
        var existing = new JournalTrade();
        existing.setId(1L);
        existing.setEntryPrice(new BigDecimal("100"));
        existing.setStopPrice(new BigDecimal("95"));
        existing.setInitialRisk(new BigDecimal("5.00"));
        existing.setEntryShares(100);
        existing.setIsOpen(1);
        when(repo.findActiveById(1L)).thenReturn(Optional.of(existing));

        var req = new JournalTradeUpdateRequest();
        req.setStopPrice(new BigDecimal("93"));   // widen — violation

        var ex = assertThrows(IllegalArgumentException.class,
                () -> service.update(1L, req));
        assertTrue(ex.getMessage().contains("止损"));
        verify(repo, never()).save(any());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -Dtest=JournalServiceCloseTest -q 2>&1 | tail -15
```

Expected: Compilation failure — `service.update(...)` not defined.

- [ ] **Step 3: Implement `update()` in `JournalService.java`**

Add these methods to the existing `JournalService` class:

```java
@Transactional
public JournalTradeDTO update(Long id, JournalTradeUpdateRequest req) {
    JournalTrade j = repo.findActiveById(id)
            .orElseThrow(() -> new IllegalArgumentException("trade 不存在或已删除: " + id));

    if (req.getStopPrice() != null) {
        if (req.getStopPrice().compareTo(j.getStopPrice()) < 0) {
            throw new IllegalArgumentException(
                    "止损只能收紧,不能放松(纪律红线) — 当前 "
                            + j.getStopPrice() + ",新值 " + req.getStopPrice());
        }
        j.setStopPrice(req.getStopPrice());
        // Recompute initial_risk if entry is unchanged
        j.setInitialRisk(j.getEntryPrice().subtract(req.getStopPrice())
                .setScale(2, RoundingMode.HALF_UP));
    }
    if (req.getTargetPrice() != null) j.setTargetPrice(req.getTargetPrice());
    if (req.getTags() != null) j.setTags(req.getTags());
    if (req.getSetupNotes() != null) j.setSetupNotes(req.getSetupNotes());
    if (req.getReviewNotes() != null) j.setReviewNotes(req.getReviewNotes());

    // Closing the trade
    if (req.getExitPrice() != null) {
        if (j.getIsOpen() != 1) {
            throw new IllegalArgumentException("该 trade 已平仓,不能再设 exitPrice");
        }
        j.setExitPrice(req.getExitPrice());
        j.setExitDate(req.getExitDate() != null ? req.getExitDate() : LocalDateTime.now());
        if (req.getExitReason() != null) {
            try {
                j.setExitReason(JournalTrade.ExitReason.valueOf(req.getExitReason()));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("exitReason 非法: " + req.getExitReason());
            }
        } else {
            j.setExitReason(JournalTrade.ExitReason.manual);
        }
        BigDecimal pnl = req.getExitPrice().subtract(j.getEntryPrice())
                .multiply(new BigDecimal(j.getEntryShares()))
                .setScale(2, RoundingMode.HALF_UP);
        j.setPnlAmount(pnl);
        BigDecimal totalRisk = j.getInitialRisk()
                .multiply(new BigDecimal(j.getEntryShares()));
        if (totalRisk.signum() == 0) {
            throw new IllegalArgumentException("initialRisk * shares = 0,无法算 R");
        }
        j.setRMultiple(pnl.divide(totalRisk, 4, RoundingMode.HALF_UP));
        j.setIsOpen(0);
    }
    return JournalTradeDTO.from(repo.save(j));
}

@Transactional
public void softDelete(Long id) {
    JournalTrade j = repo.findActiveById(id)
            .orElseThrow(() -> new IllegalArgumentException("trade 不存在: " + id));
    j.setIsDeleted(1);
    repo.save(j);
}

public JournalTradeDTO findOne(Long id) {
    return JournalTradeDTO.from(
            repo.findActiveById(id)
                    .orElseThrow(() -> new IllegalArgumentException("trade 不存在: " + id)));
}
```

- [ ] **Step 4: Run tests — expect pass**

```bash
mvn test -Dtest=JournalServiceCloseTest -q 2>&1 | tail -15
```

Expected: 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/quant/service/journal/JournalService.java \
        src/test/java/com/quant/service/journal/JournalServiceCloseTest.java
git commit -m "feat(journal): close-trade R-multiple + soft-delete + stop-loss tightening"
```

---

## Task 7: `JournalService` — list with filters

**Files:**
- Modify: `src/main/java/com/quant/service/journal/JournalService.java`
- Create: `src/test/java/com/quant/service/journal/JournalServiceListTest.java`

- [ ] **Step 1: Write failing test for list-with-filters**

```java
package com.quant.service.journal;

import com.quant.entity.JournalTrade;
import com.quant.repository.JournalTradeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JournalServiceListTest {

    @Mock JournalTradeRepository repo;
    @InjectMocks JournalService service;

    @Test
    void list_passesFiltersToRepo() {
        when(repo.findAllActive(any())).thenReturn(org.springframework.data.domain.Page.empty());
        var page = service.list("PAPER", true, null, null, null,
                org.springframework.data.domain.PageRequest.of(0, 20));
        assertNotNull(page);
        verify(repo).findAllActive(any());
    }

    @Test
    void listOpen_returnsOpenTrades() {
        var j = new JournalTrade();
        j.setId(1L);
        j.setMode(JournalTrade.Mode.REAL);
        when(repo.findAllOpen()).thenReturn(List.of(j));
        var result = service.listOpen();
        assertEquals(1, result.size());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -Dtest=JournalServiceListTest -q 2>&1 | tail -10
```

Expected: Compilation failure.

- [ ] **Step 3: Add `list()` and `listOpen()` to `JournalService.java`**

```java
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import com.quant.entity.JournalTrade;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;

@Transactional(readOnly = true)
public Page<JournalTradeDTO> list(String mode, Boolean isOpen, String tag,
                                  java.time.LocalDate from, java.time.LocalDate to,
                                  Pageable pageable) {
    Specification<JournalTrade> spec = (root, q, cb) -> {
        List<Predicate> ps = new ArrayList<>();
        ps.add(cb.equal(root.get("isDeleted"), 0));
        if (mode != null && !mode.isBlank()) {
            ps.add(cb.equal(root.get("mode"),
                    JournalTrade.Mode.valueOf(mode)));
        }
        if (isOpen != null) {
            ps.add(cb.equal(root.get("isOpen"), isOpen ? 1 : 0));
        }
        if (from != null) {
            ps.add(cb.greaterThanOrEqualTo(root.get("entryDate"), from.atStartOfDay()));
        }
        if (to != null) {
            ps.add(cb.lessThan(root.get("entryDate"), to.plusDays(1).atStartOfDay()));
        }
        // tag filter: simple LIKE on the tags column (works for single-tag queries)
        if (tag != null && !tag.isBlank()) {
            ps.add(cb.like(root.get("tags"), "%" + tag + "%"));
        }
        return cb.and(ps.toArray(new Predicate[0]));
    };
    return repo.findAll(spec, pageable).map(JournalTradeDTO::from);
}

@Transactional(readOnly = true)
public List<JournalTradeDTO> listOpen() {
    return repo.findAllOpen().stream().map(JournalTradeDTO::from).toList();
}
```

- [ ] **Step 4: Run tests — expect pass**

```bash
mvn test -Dtest=JournalServiceListTest -q 2>&1 | tail -10
```

Expected: 2 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/quant/service/journal/JournalService.java \
        src/test/java/com/quant/service/journal/JournalServiceListTest.java
git commit -m "feat(journal): list with mode/open/tag/date filters"
```

---

## Task 8: `JournalStatsService` — win rate, avg R, EV

**Files:**
- Create: `src/main/java/com/quant/service/journal/JournalStatsService.java`
- Create: `src/test/java/com/quant/service/journal/JournalStatsServiceTest.java`

- [ ] **Step 1: Write failing test**

```java
package com.quant.service.journal;

import com.quant.dto.journal.JournalStatsDTO;
import com.quant.entity.JournalTrade;
import com.quant.repository.JournalTradeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JournalStatsServiceTest {

    @Mock JournalTradeRepository repo;
    @InjectMocks JournalStatsService service;

    private JournalTrade trade(BigDecimal r) {
        var j = new JournalTrade();
        j.setMode(JournalTrade.Mode.REAL);
        j.setRMultiple(r);
        j.setExitDate(LocalDateTime.of(2026, 6, 30, 15, 0));
        return j;
    }

    @Test
    void stats_empty_returnsZeros() {
        when(repo.findAllClosedOrdered()).thenReturn(List.of());
        var s = service.stats(null);
        assertEquals(0, s.getTotalTrades());
        assertEquals(BigDecimal.ZERO, s.getWinRate());
    }

    @Test
    void stats_mixedCalculatesWinRateAndEv() {
        // 4 trades: +3R, +1R, -1R, -1R  →  win_rate=0.5, avg_win=+2, avg_loss=-1
        //  EV = 0.5 * 2 + 0.5 * -1 = 0.5
        when(repo.findAllClosedOrdered()).thenReturn(List.of(
                trade(new BigDecimal("3")),
                trade(new BigDecimal("1")),
                trade(new BigDecimal("-1")),
                trade(new BigDecimal("-1"))));
        var s = service.stats(null);
        assertEquals(4, s.getTotalTrades());
        assertEquals(2, s.getWins());
        assertEquals(2, s.getLosses());
        assertEquals(0, s.getWinRate().compareTo(new BigDecimal("0.5000")));
        assertEquals(0, s.getAverageWinR().compareTo(new BigDecimal("2.0000")));
        assertEquals(0, s.getAverageLossR().compareTo(new BigDecimal("-1.0000")));
        assertEquals(0, s.getExpectedValue().compareTo(new BigDecimal("0.5000")));
    }
}
```

- [ ] **Step 2: Run test — expect fail**

```bash
mvn test -Dtest=JournalStatsServiceTest -q 2>&1 | tail -10
```

- [ ] **Step 3: Implement `JournalStatsService.stats()`**

```java
package com.quant.service.journal;

import com.quant.dto.journal.JournalStatsDTO;
import com.quant.entity.JournalTrade;
import com.quant.repository.JournalTradeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
@RequiredArgsConstructor
public class JournalStatsService {

    private final JournalTradeRepository repo;

    public JournalStatsDTO stats(JournalTrade.Mode mode) {
        List<JournalTrade> closed = mode == null
                ? repo.findAllClosedOrdered()
                : repo.findClosedByMode(mode);

        if (closed.isEmpty()) {
            return JournalStatsDTO.builder()
                    .totalTrades(0).wins(0).losses(0)
                    .winRate(BigDecimal.ZERO)
                    .averageR(BigDecimal.ZERO)
                    .averageWinR(BigDecimal.ZERO)
                    .averageLossR(BigDecimal.ZERO)
                    .expectedValue(BigDecimal.ZERO)
                    .maxDrawdown(BigDecimal.ZERO)
                    .longestWinStreak(0L).longestLossStreak(0L)
                    .build();
        }

        int total = closed.size();
        int wins = 0, losses = 0;
        BigDecimal sumR = BigDecimal.ZERO;
        BigDecimal sumWin = BigDecimal.ZERO;
        BigDecimal sumLoss = BigDecimal.ZERO;
        long winStreak = 0, lossStreak = 0, maxWinStreak = 0, maxLossStreak = 0;

        for (JournalTrade t : closed) {
            BigDecimal r = t.getRMultiple() != null ? t.getRMultiple() : BigDecimal.ZERO;
            sumR = sumR.add(r);
            if (r.signum() > 0) {
                wins++; sumWin = sumWin.add(r);
                winStreak++; lossStreak = 0;
                if (winStreak > maxWinStreak) maxWinStreak = winStreak;
            } else if (r.signum() < 0) {
                losses++; sumLoss = sumLoss.add(r);
                lossStreak++; winStreak = 0;
                if (lossStreak > maxLossStreak) maxLossStreak = lossStreak;
            } else {
                winStreak = 0; lossStreak = 0;
            }
        }

        BigDecimal winRate = BigDecimal.valueOf(wins)
                .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
        BigDecimal avgR = sumR.divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
        BigDecimal avgWin = wins == 0 ? BigDecimal.ZERO
                : sumWin.divide(BigDecimal.valueOf(wins), 4, RoundingMode.HALF_UP);
        BigDecimal avgLoss = losses == 0 ? BigDecimal.ZERO
                : sumLoss.divide(BigDecimal.valueOf(losses), 4, RoundingMode.HALF_UP);
        BigDecimal lossRate = BigDecimal.ONE.subtract(winRate);
        BigDecimal ev = winRate.multiply(avgWin).add(lossRate.multiply(avgLoss))
                .setScale(4, RoundingMode.HALF_UP);

        BigDecimal maxDD = computeMaxDrawdown(closed);

        return JournalStatsDTO.builder()
                .totalTrades(total).wins(wins).losses(losses)
                .winRate(winRate).averageR(avgR)
                .averageWinR(avgWin).averageLossR(avgLoss)
                .expectedValue(ev).maxDrawdown(maxDD)
                .longestWinStreak(maxWinStreak).longestLossStreak(maxLossStreak)
                .build();
    }

    private BigDecimal computeMaxDrawdown(List<JournalTrade> closed) {
        BigDecimal cum = BigDecimal.ZERO;
        BigDecimal peak = BigDecimal.ZERO;
        BigDecimal maxDD = BigDecimal.ZERO;
        for (JournalTrade t : closed) {
            BigDecimal r = t.getRMultiple() != null ? t.getRMultiple() : BigDecimal.ZERO;
            cum = cum.add(r);
            if (cum.compareTo(peak) > 0) peak = cum;
            BigDecimal dd = cum.subtract(peak);
            if (dd.compareTo(maxDD) < 0) maxDD = dd;
        }
        return maxDD.setScale(4, RoundingMode.HALF_UP);
    }
}
```

- [ ] **Step 4: Run test — expect pass**

```bash
mvn test -Dtest=JournalStatsServiceTest -q 2>&1 | tail -10
```

Expected: 2 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/quant/service/journal/JournalStatsService.java \
        src/test/java/com/quant/service/journal/JournalStatsServiceTest.java
git commit -m "feat(journal): stats — win rate, avg R, EV, max drawdown, streaks"
```

---

## Task 9: `JournalStatsService` — equity curve + R distribution

**Files:**
- Modify: `src/main/java/com/quant/service/journal/JournalStatsService.java`
- Modify: `src/test/java/com/quant/service/journal/JournalStatsServiceTest.java`

- [ ] **Step 1: Add failing tests**

Append to `JournalStatsServiceTest.java`:

```java
@Test
void equityCurve_cumulativeR() {
    when(repo.findAllClosedOrdered()).thenReturn(List.of(
            trade(new BigDecimal("1")),
            trade(new BigDecimal("-0.5")),
            trade(new BigDecimal("2"))));
    var pts = service.equityCurve(null);
    assertEquals(3, pts.size());
    assertEquals(0, pts.get(0).getCumulativeR().compareTo(new BigDecimal("1.0000")));
    assertEquals(0, pts.get(1).getCumulativeR().compareTo(new BigDecimal("0.5000")));
    assertEquals(0, pts.get(2).getCumulativeR().compareTo(new BigDecimal("2.5000")));
}

@Test
void rDistribution_sevenBuckets() {
    when(repo.findAllClosedOrdered()).thenReturn(List.of(
            trade(new BigDecimal("-3")),     // <-2
            trade(new BigDecimal("-1.5")),   // -2~-1
            trade(new BigDecimal("-0.3")),   // -1~0
            trade(new BigDecimal("0.5")),    // 0~1
            trade(new BigDecimal("1.5")),    // 1~2
            trade(new BigDecimal("2.5")),    // 2~3
            trade(new BigDecimal("4"))       // >3
    ));
    var bk = service.rDistribution(null);
    assertEquals(7, bk.size());
    assertEquals(1L, bk.stream().filter(b -> b.getLabel().equals("<-2R")).findFirst().get().getCount());
    assertEquals(1L, bk.stream().filter(b -> b.getLabel().equals("-2~-1R")).findFirst().get().getCount());
    assertEquals(1L, bk.stream().filter(b -> b.getLabel().equals(">3R")).findFirst().get().getCount());
}
```

- [ ] **Step 2: Run test — expect compile fail**

```bash
mvn test -Dtest=JournalStatsServiceTest -q 2>&1 | tail -10
```

- [ ] **Step 3: Add methods to `JournalStatsService.java`**

```java
import com.quant.dto.journal.EquityCurvePoint;
import com.quant.dto.journal.RDistributionBucket;
import java.time.format.DateTimeFormatter;

public List<EquityCurvePoint> equityCurve(JournalTrade.Mode mode) {
    List<JournalTrade> closed = mode == null
            ? repo.findAllClosedOrdered()
            : repo.findClosedByMode(mode);
    BigDecimal cum = BigDecimal.ZERO;
    DateTimeFormatter fmt = DateTimeFormatter.ISO_LOCAL_DATE;
    List<EquityCurvePoint> out = new java.util.ArrayList<>();
    int idx = 1;
    for (JournalTrade t : closed) {
        BigDecimal r = t.getRMultiple() != null ? t.getRMultiple() : BigDecimal.ZERO;
        cum = cum.add(r);
        out.add(new EquityCurvePoint(idx++, t.getId(),
                t.getExitDate() != null ? t.getExitDate().toLocalDate().format(fmt) : null,
                cum.setScale(4, RoundingMode.HALF_UP)));
    }
    return out;
}

public List<RDistributionBucket> rDistribution(JournalTrade.Mode mode) {
    List<JournalTrade> closed = mode == null
            ? repo.findAllClosedOrdered()
            : repo.findClosedByMode(mode);
    long[] buckets = new long[7];   // <-2, -2~-1, -1~0, 0~1, 1~2, 2~3, >3
    for (JournalTrade t : closed) {
        BigDecimal r = t.getRMultiple() != null ? t.getRMultiple() : BigDecimal.ZERO;
        double rd = r.doubleValue();
        if      (rd < -2) buckets[0]++;
        else if (rd < -1) buckets[1]++;
        else if (rd <  0) buckets[2]++;
        else if (rd <  1) buckets[3]++;
        else if (rd <  2) buckets[4]++;
        else if (rd <  3) buckets[5]++;
        else              buckets[6]++;
    }
    String[] labels = {"<-2R", "-2~-1R", "-1~0R", "0~1R", "1~2R", "2~3R", ">3R"};
    List<RDistributionBucket> out = new java.util.ArrayList<>();
    for (int i = 0; i < 7; i++) out.add(new RDistributionBucket(labels[i], buckets[i]));
    return out;
}
```

- [ ] **Step 4: Run tests — expect pass**

```bash
mvn test -Dtest=JournalStatsServiceTest -q 2>&1 | tail -10
```

Expected: 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/quant/service/journal/JournalStatsService.java \
        src/test/java/com/quant/service/journal/JournalStatsServiceTest.java
git commit -m "feat(journal): equity curve + R distribution buckets"
```

---

## Task 10: Controller — CRUD endpoints

**Files:**
- Create: `src/main/java/com/quant/controller/JournalController.java`
- Create: `src/test/java/com/quant/controller/JournalControllerTest.java`

- [ ] **Step 1: Write failing controller test**

```java
package com.quant.controller;

import com.quant.dto.journal.JournalTradeDTO;
import com.quant.service.journal.JournalService;
import com.quant.service.journal.JournalStatsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(JournalController.class)
@AutoConfigureMockMvc(addFilters = false)
class JournalControllerTest {

    @Autowired MockMvc mvc;
    @MockBean JournalService service;
    @MockBean JournalStatsService stats;

    @Test
    @WithMockUser
    void post_trade_returnsCreated() throws Exception {
        var dto = JournalTradeDTO.builder().id(1L).stockCode("600519").build();
        when(service.create(any(), any())).thenReturn(dto);

        mvc.perform(post("/api/journal/trades")
                .contentType("application/json")
                .content("""
                    {"mode":"REAL","stockCode":"600519","entryPrice":100,
                     "stopPrice":95,"targetPrice":115,"entryShares":100}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    @WithMockUser
    void list_returnsPage() throws Exception {
        when(service.list(any(), any(), any(), any(), any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());
        mvc.perform(get("/api/journal/trades"))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser
    void delete_trade_returnsNoContent() throws Exception {
        mvc.perform(delete("/api/journal/trades/1"))
            .andExpect(status().isNoContent());
    }
}
```

- [ ] **Step 2: Run test — expect compile fail**

```bash
mvn test -Dtest=JournalControllerTest -q 2>&1 | tail -10
```

- [ ] **Step 3: Create `JournalController.java`**

```java
package com.quant.controller;

import com.quant.dto.journal.*;
import com.quant.entity.JournalTrade;
import com.quant.service.journal.JournalService;
import com.quant.service.journal.JournalStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/journal")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class JournalController {

    private final JournalService service;
    private final JournalStatsService stats;

    @PostMapping("/trades")
    public JournalTradeDTO create(@RequestBody JournalTradeCreateRequest req,
                                  @AuthenticationPrincipal UserDetails user) {
        return service.create(req, user != null ? user.getUsername() : "anonymous");
    }

    @PutMapping("/trades/{id}")
    public JournalTradeDTO update(@PathVariable Long id,
                                  @RequestBody JournalTradeUpdateRequest req) {
        return service.update(id, req);
    }

    @GetMapping("/trades/{id}")
    public JournalTradeDTO get(@PathVariable Long id) {
        return service.findOne(id);
    }

    @GetMapping("/trades")
    public Page<JournalTradeDTO> list(
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) Boolean isOpen,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable p = PageRequest.of(page, Math.min(size, 100));
        return service.list(mode, isOpen, tag, from, to, p);
    }

    @GetMapping("/trades/open")
    public List<JournalTradeDTO> listOpen() {
        return service.listOpen();
    }

    @DeleteMapping("/trades/{id}")
    public org.springframework.http.ResponseEntity<Void> delete(@PathVariable Long id) {
        service.softDelete(id);
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    @GetMapping("/stats")
    public JournalStatsDTO stats(
            @RequestParam(required = false) String mode) {
        JournalTrade.Mode m = (mode == null || mode.isBlank())
                ? null : JournalTrade.Mode.valueOf(mode);
        return stats.stats(m);
    }

    @GetMapping("/equity-curve")
    public List<EquityCurvePoint> equityCurve(
            @RequestParam(required = false) String mode) {
        JournalTrade.Mode m = (mode == null || mode.isBlank())
                ? null : JournalTrade.Mode.valueOf(mode);
        return stats.equityCurve(m);
    }

    @GetMapping("/r-distribution")
    public List<RDistributionBucket> rDistribution(
            @RequestParam(required = false) String mode) {
        JournalTrade.Mode m = (mode == null || mode.isBlank())
                ? null : JournalTrade.Mode.valueOf(mode);
        return stats.rDistribution(m);
    }
}
```

- [ ] **Step 4: Run test — expect pass**

```bash
mvn test -Dtest=JournalControllerTest -q 2>&1 | tail -10
```

Expected: 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/quant/controller/JournalController.java \
        src/test/java/com/quant/controller/JournalControllerTest.java
git commit -m "feat(journal): controller — 9 CRUD/stats endpoints"
```

---

## Task 11: POOL_SYNC — pending fills + sync-from-fill

**Files:**
- Modify: `src/main/java/com/quant/service/journal/JournalService.java`
- Modify: `src/main/java/com/quant/controller/JournalController.java`

- [ ] **Step 1: Investigate `InvestPositionFillRepository`**

```bash
grep -n "findBy" src/main/java/com/quant/repository/InvestPositionFillRepository.java
```

Identify which method returns recent fills. Likely candidate: a `findAllByOrderByFilledAtDesc()` or similar. If none exists, add to the repository:

```java
@Query("SELECT f FROM InvestPositionFill f WHERE f.filledAt >= :since ORDER BY f.filledAt DESC")
List<InvestPositionFill> findRecentSince(@Param("since") java.time.LocalDateTime since);
```

For the plan we assume `findRecentSince` exists or you add it; the consuming code below adapts.

- [ ] **Step 2: Write failing test for `pendingFills()`**

Append to `JournalServiceListTest.java`:

```java
@Test
void pendingFills_excludesAlreadySynced() {
    var fill = new com.quant.entity.InvestPositionFill();
    fill.setId(10L);
    fill.setStockCode("600519");
    fill.setAction("clear");
    fill.setPrice(new BigDecimal("110"));
    fill.setLots(new BigDecimal("2"));
    fill.setFilledAt(java.time.LocalDateTime.of(2026, 6, 30, 15, 0));

    when(fillRepo.findRecentSince(any())).thenReturn(java.util.List.of(fill));
    when(repo.findBySourceRef(10L)).thenReturn(java.util.Optional.empty());

    var out = service.pendingFills();
    assertEquals(1, out.size());
    assertEquals(10L, out.get(0).getFillId());
}
```

- [ ] **Step 3: Run test — expect compile fail (need fill repo injection)**

- [ ] **Step 4: Inject `InvestPositionFillRepository` into `JournalService`**

Add field:

```java
private final com.quant.repository.InvestPositionFillRepository fillRepo;
```

(also add the import). Lombok `@RequiredArgsConstructor` picks up the new final field automatically.

Add these methods to `JournalService`:

```java
@Transactional(readOnly = true)
public List<PendingFillDTO> pendingFills() {
    var since = java.time.LocalDateTime.now().minusDays(30);
    var fills = fillRepo.findRecentSince(since);
    return fills.stream()
            .filter(f -> "clear".equalsIgnoreCase(f.getAction())
                      || "reduce".equalsIgnoreCase(f.getAction()))
            .filter(f -> repo.findBySourceRef(f.getId()).isEmpty())
            .map(f -> PendingFillDTO.builder()
                    .fillId(f.getId())
                    .poolType(f.getPoolType())
                    .stockCode(f.getStockCode())
                    .stockName(f.getStockName())
                    .action(f.getAction())
                    .price(f.getPrice())
                    .lots(f.getLots())
                    .filledAt(f.getFilledAt())
                    .note(f.getNote())
                    .build())
            .toList();
}

/**
 * Sync a single fill from invest_position_fill into journal_trade.
 *
 * Direct entity manipulation (not service.create + service.update) because:
 *  - We're recording history, not enforcing new-trade discipline
 *  - The entry price/stop are derived from existing pool data
 *  - Avoids any double-transaction bookkeeping
 */
@Transactional
public JournalTradeDTO syncFromFill(Long fillId, String username) {
    var fill = fillRepo.findById(fillId)
            .orElseThrow(() -> new IllegalArgumentException("fill 不存在: " + fillId));
    if (repo.findBySourceRef(fillId).isPresent()) {
        throw new IllegalStateException("该 fill 已同步过(重复同步)");
    }
    BigDecimal entry = fill.getAvgCost() != null ? fill.getAvgCost() : fill.getPrice();
    BigDecimal stop  = entry.multiply(new BigDecimal("0.95"))
            .setScale(2, java.math.RoundingMode.HALF_UP);

    var j = new JournalTrade();
    j.setMode(JournalTrade.Mode.REAL);
    j.setSource("POOL_SYNC");
    j.setSourceRefId(fillId);
    j.setStockCode(fill.getStockCode());
    j.setStockName(fill.getStockName());
    j.setEntryPrice(entry);
    j.setStopPrice(stop);
    j.setTargetPrice(null);
    j.setEntryShares(fill.getLots() != null
            ? fill.getLots().multiply(new BigDecimal("100")).intValue() : 0);
    j.setEntryDate(fill.getFilledAt());
    j.setInitialRisk(entry.subtract(stop).setScale(2, java.math.RoundingMode.HALF_UP));
    j.setIsOpen(1);
    j.setSetupNotes("POOL_SYNC from " + fill.getPoolType() + " fillId=" + fillId);
    j.setCreatedBy(username);

    if ("clear".equalsIgnoreCase(fill.getAction())) {
        BigDecimal pnl = fill.getPrice().subtract(entry)
                .multiply(new BigDecimal(j.getEntryShares()))
                .setScale(2, java.math.RoundingMode.HALF_UP);
        j.setExitPrice(fill.getPrice());
        j.setExitDate(fill.getFilledAt());
        j.setExitReason(JournalTrade.ExitReason.manual);
        j.setPnlAmount(pnl);
        BigDecimal totalRisk = j.getInitialRisk()
                .multiply(new BigDecimal(j.getEntryShares()));
        if (totalRisk.signum() > 0) {
            j.setRMultiple(pnl.divide(totalRisk, 4, java.math.RoundingMode.HALF_UP));
        }
        j.setIsOpen(0);
        j.setReviewNotes("从 " + fill.getPoolType() + " 同步的清仓记录");
    }
    return JournalTradeDTO.from(repo.save(j));
}
```

- [ ] **Step 5: Wire into controller**

Add to `JournalController`:

```java
@GetMapping("/pending-fills")
public List<PendingFillDTO> pendingFills() {
    return service.pendingFills();
}

@PostMapping("/sync-from-fill/{fillId}")
public JournalTradeDTO syncFromFill(@PathVariable Long fillId,
                                     @AuthenticationPrincipal UserDetails user) {
    return service.syncFromFill(fillId,
            user != null ? user.getUsername() : "anonymous");
}
```

- [ ] **Step 6: Run test — expect pass**

```bash
mvn test -Dtest=JournalServiceListTest -q 2>&1 | tail -10
```

Expected: 3 tests pass (existing 2 + new 1).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/quant/service/journal/JournalService.java \
        src/main/java/com/quant/controller/JournalController.java \
        src/test/java/com/quant/service/journal/JournalServiceListTest.java \
        src/main/java/com/quant/repository/InvestPositionFillRepository.java
git commit -m "feat(journal): POOL_SYNC from invest_position_fill"
```

---

## Task 12: Cron service — refresh + auto-close + Server酱

**Files:**
- Create: `src/main/java/com/quant/service/journal/JournalCronService.java`
- Create: `src/test/java/com/quant/service/journal/JournalCronServiceTest.java`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Add cron key to `application.yml`**

Find the `notification` block (or near other module cron keys) and add:

```yaml
journal:
  refresh-cron: "0 30 15 * * MON-FRI"
  refresh-enabled: true
```

Also add to `config/NotificationProperties.java` (or create new `JournalProperties.java` if you prefer — match the existing pattern of one Properties per module). The simplest path is a new file:

`src/main/java/com/quant/config/JournalProperties.java`:

```java
package com.quant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "journal")
public class JournalProperties {
    private String refreshCron = "0 30 15 * * MON-FRI";
    private Boolean refreshEnabled = true;
}
```

Register it: open `GupiaoQuantApplication.java`, add `@EnableConfigurationProperties(JournalProperties.class)` if not already present.

- [ ] **Step 2: Write failing cron test**

```java
package com.quant.service.journal;

import com.quant.entity.JournalTrade;
import com.quant.repository.JournalTradeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JournalCronServiceTest {

    @Mock JournalTradeRepository repo;
    @Mock com.quant.service.notify.NotificationDispatcher dispatcher;
    @InjectMocks JournalCronService cron;

    @Test
    void refreshOpenTrades_autoClosesOnTargetHit() {
        var open = new JournalTrade();
        open.setId(1L);
        open.setMode(JournalTrade.Mode.REAL);
        open.setStockCode("600519");
        open.setEntryPrice(new BigDecimal("100"));
        open.setStopPrice(new BigDecimal("95"));
        open.setTargetPrice(new BigDecimal("115"));
        open.setInitialRisk(new BigDecimal("5.00"));
        open.setEntryShares(100);
        open.setIsOpen(1);

        when(repo.findAllOpen()).thenReturn(List.of(open));
        when(repo.save(any(JournalTrade.class))).thenAnswer(inv -> inv.getArgument(0));

        // Stub the price fetch (assume current=120 > target=115)
        cron.refreshOpenTrades("600519", new BigDecimal("120"));

        ArgumentCaptor<JournalTrade> cap = ArgumentCaptor.forClass(JournalTrade.class);
        verify(repo).save(cap.capture());
        var saved = cap.getValue();
        assertEquals(0, saved.getIsOpen());
        assertEquals(JournalTrade.ExitReason.target_hit, saved.getExitReason());
        assertEquals(0, saved.getExitPrice().compareTo(new BigDecimal("115")));
        verify(dispatcher).sendServerChan(any());
    }
}
```

- [ ] **Step 3: Run test — expect compile fail**

- [ ] **Step 4: Create `JournalCronService.java`**

```java
package com.quant.service.journal;

import com.quant.config.JournalProperties;
import com.quant.entity.JournalTrade;
import com.quant.repository.JournalTradeRepository;
import com.quant.service.notify.NotificationDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Slf4j
@Service
@RequiredArgsConstructor
public class JournalCronService {

    private final JournalTradeRepository repo;
    private final NotificationDispatcher dispatcher;
    private final JournalProperties props;

    @Scheduled(cron = "${journal.refresh-cron:0 30 15 * * MON-FRI}")
    @Transactional
    public void scheduledRefresh() {
        if (props.getRefreshEnabled() == null || !props.getRefreshEnabled()) return;
        log.info("[JournalCron] 盘后刷新开始");
        for (JournalTrade t : repo.findAllOpen()) {
            try {
                BigDecimal current = fetchPrice(t.getStockCode());
                if (current == null) continue;
                refreshOpenTrade(t, current);
            } catch (Exception e) {
                log.warn("[JournalCron] {} 处理失败: {}", t.getStockCode(), e.getMessage());
            }
        }
        log.info("[JournalCron] 盘后刷新结束");
    }

    /** Test-friendly overload. */
    public void refreshOpenTrades(String stockCode, BigDecimal currentPrice) {
        for (JournalTrade t : repo.findAllOpen()) {
            if (!t.getStockCode().equals(stockCode)) continue;
            refreshOpenTrade(t, currentPrice);
        }
    }

    private void refreshOpenTrade(JournalTrade t, BigDecimal current) {
        if (t.getTargetPrice() != null && current.compareTo(t.getTargetPrice()) >= 0) {
            t.setExitPrice(t.getTargetPrice());
            t.setExitDate(java.time.LocalDateTime.now());
            t.setExitReason(JournalTrade.ExitReason.target_hit);
            t.setIsOpen(0);
            BigDecimal pnl = t.getTargetPrice().subtract(t.getEntryPrice())
                    .multiply(new BigDecimal(t.getEntryShares()))
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            t.setPnlAmount(pnl);
            BigDecimal totalRisk = t.getInitialRisk()
                    .multiply(new BigDecimal(t.getEntryShares()));
            if (totalRisk.signum() > 0) {
                t.setRMultiple(pnl.divide(totalRisk, 4, java.math.RoundingMode.HALF_UP));
            }
            t.setReviewNotes("系统自动平仓(目标触达)");
            repo.save(t);
            dispatcher.sendServerChan(String.format(
                    "[自动平仓] %s (%s)\n入场 %.2f → 目标 %.2f\nR 倍数 %s",
                    t.getStockCode(),
                    t.getStockName() != null ? t.getStockName() : "",
                    t.getEntryPrice(),
                    t.getTargetPrice(),
                    t.getRMultiple()));
        }
    }

    private BigDecimal fetchPrice(String stockCode) {
        try {
            // Reuse existing quote service — implement via WebClient or HTTP call.
            // Minimal: call RestTemplate to /gp/api/xiebo-invest/quote?keyword=
            var rest = new org.springframework.web.client.RestTemplate();
            String url = "http://localhost:8080/gp/api/xiebo-invest/quote?keyword="
                    + java.net.URLEncoder.encode(stockCode, java.nio.charset.StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            var body = rest.getForObject(url, java.util.Map.class);
            if (body == null) return null;
            Object p = body.get("price");
            if (p == null && body.get("quote") instanceof java.util.Map q) p = q.get("price");
            return p == null ? null : new BigDecimal(p.toString());
        } catch (Exception e) {
            log.debug("[JournalCron] 拉价失败 {}: {}", stockCode, e.getMessage());
            return null;
        }
    }
}
```

- [ ] **Step 5: Run test — expect pass**

```bash
mvn test -Dtest=JournalCronServiceTest -q 2>&1 | tail -15
```

Expected: 1 test passes.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/quant/service/journal/JournalCronService.java \
        src/main/java/com/quant/config/JournalProperties.java \
        src/main/java/com/quant/service/notify/ \
        src/test/java/com/quant/service/journal/JournalCronServiceTest.java \
        src/main/resources/application.yml \
        src/main/java/com/quant/GupiaoQuantApplication.java
git commit -m "feat(journal): cron refresh + auto-close on target hit + Server酱"
```

**Note:** `NotificationDispatcher` may not exist yet under `service/notify/`. If missing, search for the existing Server酱 send pattern in the codebase:

```bash
grep -r "sendServerChan\|serverchan" src/main/java/ --include="*.java" -l
```

Whichever class exposes a `sendServerChan(String)` (or similar), inject that into `JournalCronService` instead. Adapt the test mock to match.

---

## Task 13: Security — protect `/api/journal/**`

**Files:**
- Modify: `src/main/java/com/quant/security/SecurityConfig.java`

- [ ] **Step 1: Locate permitAll + filter chain**

```bash
grep -n "permitAll\|requestMatchers\|anyRequest" src/main/java/com/quant/security/SecurityConfig.java
```

- [ ] **Step 2: Add `/api/journal/**` to authenticated list**

Find the `anyRequest().authenticated()` line (it should be the last `authorizeHttpRequests` entry). If a `permitAll` list is in use before it, add `/api/journal/**` to the `authenticated()` chain. Simplest pattern:

```java
.authorizeHttpRequests(auth -> auth
    .requestMatchers("/api/auth/**", "/api/stock/search",
            "/api/quote/**", "/api/invest/pool", "/api/invest/sop/**",
            "/api/invest/big-yang/**", "/api/prosperity-strong/**",
            "/api/stock-analysis/**", "/api/market-recaps/**",
            "/api/monitor/pool/**", "/api/news/**").permitAll()
    .requestMatchers(org.springframework.http.HttpMethod.POST,
            "/api/stats/page-view").permitAll()
    .anyRequest().authenticated()
)
```

The `.anyRequest().authenticated()` line already covers `/api/journal/**`. No change needed if that line is present. If journal endpoints become reachable without auth, that's the place to look.

- [ ] **Step 3: Verify by running app + curl**

```bash
mvn spring-boot:run &
sleep 25
# Should be 401 without token
curl -i http://localhost:8080/gp/api/journal/trades 2>&1 | head -3
# Should be 200 with token (after login)
TOKEN=$(curl -s -X POST http://localhost:8080/gp/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"<your-user>","password":"<your-pass>"}' | jq -r .data.token)
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/gp/api/journal/trades | jq '.content | length'
kill %1
```

Expected: First returns 401, second returns 0 (empty page).

- [ ] **Step 4: Commit (if changes made)**

```bash
git add src/main/java/com/quant/security/SecurityConfig.java
git commit -m "feat(journal): protect /api/journal/** with .authenticated()"
```

If no code change was needed in Step 2, skip the commit.

---

## Task 14: Frontend — `journal.html` structure

**Files:**
- Create: `src/main/resources/static/journal.html`
- Create: `src/main/resources/static/css/journal.css`

- [ ] **Step 1: Create `journal.html`**

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <meta name="theme-color" content="#1e88ff" />
  <base href="/gp/" />
  <title>交易日志 · 投资助手</title>
  <link rel="stylesheet" href="css/skin.css?v=20260630-journal-v1" />
  <link rel="stylesheet" href="css/style.css?v=20260630-journal-v1" />
  <link rel="stylesheet" href="css/position-management.css?v=20260630-journal-v1" />
  <link rel="stylesheet" href="css/journal.css?v=20260630-journal-v1" />
  <script src="https://cdn.jsdelivr.net/npm/chart.js@4"></script>
  <script src="js/layout.js?v=20260630-journal-v1" defer></script>
  <script src="js/journal.js?v=20260630-journal-v1" defer></script>
</head>
<body>
  <div id="siteHeader"></div>

  <main class="container jl-page">
    <section class="jl-hero">
      <h1>交易日志 · 复盘台</h1>
      <p>学习 → 实践 → 复盘的闭环。每笔记一笔交易,系统自动算 R 倍数、胜率、EV、最大回撤。</p>
    </section>

    <div class="jl-grid">
      <!-- LEFT: 新建交易 -->
      <aside class="jl-card jl-col-left">
        <h2>新建一笔交易</h2>
        <form id="jlNewForm" autocomplete="off">
          <div class="jl-mode-toggle">
            <label><input type="radio" name="mode" value="REAL" checked /> 实盘</label>
            <label><input type="radio" name="mode" value="PAPER" /> 模拟盘</label>
          </div>

          <label>股票代码
            <input id="jlStockCode" type="text" placeholder="600519" required />
          </label>
          <label>股票名称(可选)
            <input id="jlStockName" type="text" placeholder="贵州茅台" />
          </label>

          <label>入场价
            <input id="jlEntryPrice" type="number" step="0.01" required />
          </label>
          <label>止损价
            <input id="jlStopPrice" type="number" step="0.01" required />
          </label>
          <label>目标价(可选)
            <input id="jlTargetPrice" type="number" step="0.01" />
          </label>
          <label>股数(向下取整到 100)
            <input id="jlEntryShares" type="number" step="100" value="100" required />
          </label>
          <label>账户余额(可选)
            <input id="jlAccount" type="number" step="0.01" />
          </label>
          <label>单笔风险 %(可选)
            <input id="jlRiskPct" type="number" step="0.01" placeholder="1.00" />
          </label>
          <label>标签(逗号分隔,可选)
            <input id="jlTags" type="text" placeholder="海龟,练习1" />
          </label>
          <label>开仓前思考(可选)
            <textarea id="jlSetupNotes" rows="3"></textarea>
          </label>

          <fieldset class="jl-redlines">
            <legend>5 条纪律红线(全部勾选才能提交)</legend>
            <label><input type="checkbox" id="rl1" /> ① R:R ≥ 1:3</label>
            <label><input type="checkbox" id="rl2" /> ② 单笔风险 ≤ 2%</label>
            <label><input type="checkbox" id="rl3" /> ③ 止损已明确设好</label>
            <label><input type="checkbox" id="rl4" /> ④ 本笔不加仓摊低成本</label>
            <label><input type="checkbox" id="rl5" /> ⑤ 这是一笔计划内的交易</label>
          </fieldset>

          <div id="jlFormError" class="jl-error"></div>
          <button type="submit" id="jlSubmit" class="jl-btn-primary" disabled>保存交易</button>
        </form>
      </aside>

      <!-- CENTER: 交易列表 -->
      <section class="jl-col-center">
        <div class="jl-tabs">
          <button data-tab="open" class="jl-tab-active">进行中</button>
          <button data-tab="closed">已平仓</button>
          <button data-tab="all">全部</button>
          <button id="jlSyncBtn" class="jl-btn-ghost" style="margin-left:auto;">从三池同步</button>
        </div>
        <div id="jlTradeList" class="jl-list"></div>
      </section>

      <!-- RIGHT: 统计 -->
      <aside class="jl-col-right">
        <div class="jl-mode-filter">
          <label>Mode:
            <select id="jlStatsMode">
              <option value="">ALL</option>
              <option value="REAL">REAL</option>
              <option value="PAPER">PAPER</option>
            </select>
          </label>
        </div>

        <div class="jl-stats-grid">
          <div class="jl-stat-card"><div class="label">胜率</div><div class="value" id="jlWinRate">-</div></div>
          <div class="jl-stat-card"><div class="label">平均 R</div><div class="value" id="jlAvgR">-</div></div>
          <div class="jl-stat-card"><div class="label">EV</div><div class="value" id="jlEV">-</div></div>
          <div class="jl-stat-card"><div class="label">最大回撤</div><div class="value" id="jlMaxDD">-</div></div>
        </div>

        <h3>权益曲线</h3>
        <canvas id="jlEquityCanvas" height="160"></canvas>

        <h3>R 倍数分布</h3>
        <canvas id="jlDistCanvas" height="160"></canvas>
      </aside>
    </div>
  </main>
</body>
</html>
```

- [ ] **Step 2: Create `journal.css`**

```css
.jl-page { padding: 16px; }
.jl-hero { padding: 16px 0; }
.jl-hero h1 { font-size: 22px; margin: 0 0 4px; }
.jl-hero p { color: #888; font-size: 13px; margin: 0; }

.jl-grid {
  display: grid;
  grid-template-columns: 320px 1fr 360px;
  gap: 16px;
  margin-top: 16px;
}

.jl-card, .jl-col-left, .jl-col-center, .jl-col-right {
  background: var(--card-bg, #1e1e1e);
  border: 1px solid var(--border, #2a2a2a);
  border-radius: 8px;
  padding: 16px;
}

.jl-col-left h2, .jl-col-right h3 { font-size: 14px; margin: 0 0 12px; }

.jl-mode-toggle { display: flex; gap: 12px; margin-bottom: 12px; }

#jlNewForm label {
  display: block; margin-bottom: 8px; font-size: 12px; color: #aaa;
}
#jlNewForm input, #jlNewForm textarea {
  width: 100%; box-sizing: border-box;
  background: #121212; color: #eaeaea;
  border: 1px solid #333; border-radius: 4px;
  padding: 6px 8px; font-size: 13px;
}

.jl-redlines {
  border: 1px solid #444; border-radius: 4px;
  padding: 8px 10px; margin: 12px 0;
}
.jl-redlines legend { font-size: 12px; color: #f5a623; padding: 0 4px; }
.jl-redlines label { display: block; font-size: 12px; color: #ccc; margin-bottom: 4px; }

.jl-btn-primary {
  width: 100%; padding: 8px;
  background: #1e88ff; color: #fff; border: none; border-radius: 4px;
  cursor: pointer; font-size: 13px;
}
.jl-btn-primary[disabled] { background: #444; cursor: not-allowed; }

.jl-btn-ghost {
  background: transparent; color: #aaa;
  border: 1px solid #555; border-radius: 4px;
  padding: 4px 10px; cursor: pointer; font-size: 12px;
}

.jl-error { color: #ff6b6b; font-size: 12px; margin: 8px 0; min-height: 16px; }

.jl-tabs { display: flex; gap: 4px; margin-bottom: 12px; align-items: center; }
.jl-tabs button {
  background: transparent; color: #aaa;
  border: 1px solid #444; border-radius: 4px;
  padding: 6px 12px; cursor: pointer; font-size: 12px;
}
.jl-tabs button.jl-tab-active { background: #1e88ff; color: #fff; border-color: #1e88ff; }

.jl-list { display: flex; flex-direction: column; gap: 8px; }
.jl-trade-card {
  border: 1px solid #333; border-radius: 6px; padding: 12px;
  background: #181818; font-size: 13px;
}
.jl-trade-card .jl-meta { color: #888; font-size: 11px; }
.jl-trade-card .jl-r-pos { color: #4caf50; font-weight: bold; }
.jl-trade-card .jl-r-neg { color: #ff5252; font-weight: bold; }
.jl-trade-card .jl-mode-real { background: #ff9800; color: #000; padding: 1px 6px; border-radius: 3px; font-size: 10px; }
.jl-trade-card .jl-mode-paper { background: #9c27b0; color: #fff; padding: 1px 6px; border-radius: 3px; font-size: 10px; }

.jl-stats-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-bottom: 12px; }
.jl-stat-card { background: #181818; border: 1px solid #333; border-radius: 6px; padding: 10px; }
.jl-stat-card .label { font-size: 11px; color: #888; }
.jl-stat-card .value { font-size: 18px; font-weight: bold; margin-top: 4px; }

.jl-mode-filter { margin-bottom: 12px; font-size: 12px; }
.jl-mode-filter select { background: #121212; color: #eaeaea; border: 1px solid #333; border-radius: 4px; padding: 4px 8px; }
```

- [ ] **Step 3: Verify HTML loads**

```bash
mvn spring-boot:run &
sleep 25
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/gp/journal.html
kill %1
```

Expected: 200.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/static/journal.html src/main/resources/static/css/journal.css
git commit -m "feat(journal): frontend HTML + CSS skeleton"
```

---

## Task 15: Frontend — `journal.js` (new trade form + 5 red lines)

**Files:**
- Create: `src/main/resources/static/js/journal.js`

- [ ] **Step 1: Create `journal.js` with the new-trade form logic**

```javascript
/* =============================================================
 * journal.js — 交易日志前端
 *   1) 新建表单 + 5 条红线勾选 + 客户端预校验
 *   2) 拉价 + 自动填入场价
 *   3) 提交 POST /api/journal/trades
 * ============================================================= */
(function () {
  'use strict';

  var $ = function (s) { return document.querySelector(s); };
  var API = '/gp/api/journal';

  document.addEventListener('DOMContentLoaded', function () {
    var stockInput = $('#jlStockCode');
    var entryInput = $('#jlEntryPrice');
    var stopInput  = $('#jlStopPrice');
    var targetInput= $('#jlTargetPrice');

    // Debounce stock → fetch current price → fill entry
    var stockTimer;
    if (stockInput) stockInput.addEventListener('input', function () {
      clearTimeout(stockTimer);
      var code = stockInput.value.trim();
      if (!code) return;
      stockTimer = setTimeout(function () {
        fetch('/gp/api/xiebo-invest/quote?keyword=' + encodeURIComponent(code))
          .then(function (r) { return r.ok ? r.json() : null; })
          .then(function (d) {
            if (!d) return;
            var p = d.price || d.currentPrice || (d.quote && d.quote.price);
            if (p != null && !entryInput.value) entryInput.value = Number(p).toFixed(2);
          }).catch(function () {});
      }, 280);
    });

    // Enable submit only when all 5 red lines checked
    var submit = $('#jlSubmit');
    var checks = ['#rl1','#rl2','#rl3','#rl4','#rl5'].map(function (s) { return $(s); });
    function refreshSubmit() {
      submit.disabled = checks.some(function (c) { return !c.checked; });
    }
    checks.forEach(function (c) { if (c) c.addEventListener('change', refreshSubmit); });

    // Form submit
    var form = $('#jlNewForm');
    if (form) form.addEventListener('submit', function (e) {
      e.preventDefault();
      var mode = document.querySelector('input[name="mode"]:checked').value;
      var payload = {
        mode: mode,
        stockCode: stockInput.value.trim(),
        stockName: $('#jlStockName').value.trim() || null,
        entryPrice: Number(entryInput.value),
        stopPrice:  Number(stopInput.value),
        targetPrice: targetInput.value ? Number(targetInput.value) : null,
        entryShares: Number($('#jlEntryShares').value),
        accountAtEntry: $('#jlAccount').value ? Number($('#jlAccount').value) : null,
        riskPercent: $('#jlRiskPct').value ? Number($('#jlRiskPct').value) / 100 : null,
        tags: $('#jlTags').value.trim() || null,
        setupNotes: $('#jlSetupNotes').value.trim() || null
      };
      $('#jlFormError').textContent = '';
      submit.disabled = true;
      submit.textContent = '保存中...';
      fetch(API + '/trades', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      }).then(function (r) {
        if (!r.ok) return r.text().then(function (t) { throw new Error(t || ('HTTP ' + r.status)); });
        return r.json();
      }).then(function () {
        form.reset();
        checks.forEach(function (c) { if (c) c.checked = false; });
        refreshSubmit();
        if (window.jlReload) window.jlReload();
      }).catch(function (err) {
        $('#jlFormError').textContent = '保存失败: ' + (err.message || err);
        submit.disabled = false;
        submit.textContent = '保存交易';
      });
    });
  });
})();
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/static/js/journal.js
git commit -m "feat(journal): new-trade form + 5 red lines + stock auto-fill"
```

---

## Task 16: Frontend — list, close form, stats fetch + Chart.js

**Files:**
- Modify: `src/main/resources/static/js/journal.js`

- [ ] **Step 1: Append list / stats / chart code to `journal.js`**

Add a second IIFE below the existing one (or extend the existing one):

```javascript
/* =============================================================
 * 续 — 列表 / 平仓 / 统计 / 图表
 * ============================================================= */
(function () {
  'use strict';

  var $ = function (s) { return document.querySelector(s); };
  var $$ = function (s) { return Array.prototype.slice.call(document.querySelectorAll(s)); };
  var API = '/gp/api/journal';

  var currentTab = 'open';
  var currentMode = '';
  var equityChart, distChart;

  document.addEventListener('DOMContentLoaded', function () {
    // Tab switching
    $$('.jl-tabs button[data-tab]').forEach(function (b) {
      b.addEventListener('click', function () {
        $$('.jl-tabs button[data-tab]').forEach(function (x) { x.classList.remove('jl-tab-active'); });
        b.classList.add('jl-tab-active');
        currentTab = b.dataset.tab;
        loadList();
      });
    });

    // Mode filter
    var modeSel = $('#jlStatsMode');
    if (modeSel) modeSel.addEventListener('change', function () {
      currentMode = modeSel.value;
      loadStats();
    });

    // Sync from pool
    var syncBtn = $('#jlSyncBtn');
    if (syncBtn) syncBtn.addEventListener('click', syncFromPool);

    loadList();
    loadStats();
    setInterval(refreshOpenFloating, 30000);
    window.jlReload = function () { loadList(); loadStats(); };
  });

  function loadList() {
    var url = API + '/trades?size=50';
    if (currentTab === 'open') url += '&isOpen=true';
    if (currentTab === 'closed') url += '&isOpen=false';
    fetch(url, { headers: authHeaders() })
      .then(function (r) { return r.ok ? r.json() : { content: [] }; })
      .then(function (page) { renderList(page.content || []); })
      .catch(function () { renderList([]); });
  }

  function authHeaders() {
    var t = localStorage.getItem('token');
    return t ? { 'Authorization': 'Bearer ' + t } : {};
  }

  function fmtMoney(v) {
    if (v == null) return '-';
    return 'CNY ' + Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  }
  function fmtR(v) {
    if (v == null) return '-';
    var n = Number(v);
    var cls = n >= 0 ? 'jl-r-pos' : 'jl-r-neg';
    return '<span class="' + cls + '">' + (n >= 0 ? '+' : '') + n.toFixed(2) + 'R</span>';
  }
  function esc(s) {
    if (s == null) return '';
    return String(s)
      .replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')
      .replace(/"/g,'&quot;').replace(/'/g,'&#39;');
  }

  function renderList(items) {
    var box = $('#jlTradeList');
    if (!box) return;
    if (!items.length) {
      box.innerHTML = '<div class="jl-trade-card">没有交易记录</div>';
      return;
    }
    box.innerHTML = items.map(function (t) {
      var modeBadge = t.mode === 'REAL'
        ? '<span class="jl-mode-real">REAL</span>'
        : '<span class="jl-mode-paper">PAPER</span>';
      var stateBadge = t.isOpen ? '持仓中' : '已平仓';
      var pnl = t.isOpen ? '浮盈 ' + fmtR(t.rMultiple) : '实盈 ' + fmtR(t.rMultiple);
      var closeBtn = t.isOpen
        ? '<button class="jl-btn-ghost" onclick="window.__jlClose(' + t.id + ')">平仓</button>'
        : '';
      return '<div class="jl-trade-card">' +
        '<div><strong>' + esc(t.stockCode) + '</strong> ' + esc(t.stockName || '') + ' ' + modeBadge + ' ' + stateBadge + '</div>' +
        '<div class="jl-meta">入 ' + t.entryPrice + ' / 损 ' + t.stopPrice +
        (t.targetPrice ? ' / 目标 ' + t.targetPrice : '') + ' · ' +
        t.entryShares + ' 股</div>' +
        '<div class="jl-meta">入场 ' + (t.entryDate || '').substring(0,10) +
        (t.exitDate ? ' → 平仓 ' + t.exitDate.substring(0,10) : '') + '</div>' +
        '<div style="margin-top:6px;">' + pnl + ' · 标签: ' + esc(t.tags || '-') + ' ' + closeBtn + '</div>' +
        '</div>';
    }).join('');
  }

  window.__jlClose = function (id) {
    var p = prompt('输入实际平仓价:');
    if (!p) return;
    var reason = prompt('平仓原因 (manual / stopped_out / target_hit / time_stop):', 'manual');
    var notes  = prompt('复盘笔记:') || '';
    fetch(API + '/trades/' + id, {
      method: 'PUT',
      headers: Object.assign({ 'Content-Type': 'application/json' }, authHeaders()),
      body: JSON.stringify({ exitPrice: Number(p), exitReason: reason, reviewNotes: notes })
    }).then(function (r) {
      if (!r.ok) return r.text().then(function (t) { throw new Error(t); });
      window.jlReload();
    }).catch(function (e) { alert('平仓失败: ' + e.message); });
  };

  function loadStats() {
    var url = API + '/stats';
    if (currentMode) url += '?mode=' + encodeURIComponent(currentMode);
    fetch(url, { headers: authHeaders() })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(renderStats);
    loadEquity();
    loadDistribution();
  }

  function renderStats(s) {
    if (!s) return;
    $('#jlWinRate').textContent = s.totalTrades === 0 ? '-' :
      (s.winRate * 100).toFixed(1) + '% (' + s.wins + '/' + s.totalTrades + ')';
    $('#jlAvgR').textContent = s.averageR != null ? Number(s.averageR).toFixed(2) + 'R' : '-';
    $('#jlEV').textContent = s.expectedValue != null ? Number(s.expectedValue).toFixed(2) + 'R' : '-';
    $('#jlMaxDD').textContent = s.maxDrawdown != null ? Number(s.maxDrawdown).toFixed(2) + 'R' : '-';
  }

  function loadEquity() {
    var url = API + '/equity-curve';
    if (currentMode) url += '?mode=' + encodeURIComponent(currentMode);
    fetch(url, { headers: authHeaders() })
      .then(function (r) { return r.ok ? r.json() : []; })
      .then(function (pts) {
        var ctx = $('#jlEquityCanvas').getContext('2d');
        if (equityChart) equityChart.destroy();
        equityChart = new Chart(ctx, {
          type: 'line',
          data: {
            labels: pts.map(function (p) { return p.tradeIndex; }),
            datasets: [{
              label: '累计 R', data: pts.map(function (p) { return Number(p.cumulativeR); }),
              borderColor: '#1e88ff', backgroundColor: 'rgba(30,136,255,0.1)',
              fill: true, tension: 0.2
            }]
          },
          options: { plugins: { legend: { display: false } }, scales: { y: { beginAtZero: true } } }
        });
      });
  }

  function loadDistribution() {
    var url = API + '/r-distribution';
    if (currentMode) url += '?mode=' + encodeURIComponent(currentMode);
    fetch(url, { headers: authHeaders() })
      .then(function (r) { return r.ok ? r.json() : []; })
      .then(function (b) {
        var ctx = $('#jlDistCanvas').getContext('2d');
        if (distChart) distChart.destroy();
        distChart = new Chart(ctx, {
          type: 'bar',
          data: {
            labels: b.map(function (x) { return x.label; }),
            datasets: [{ label: '数量', data: b.map(function (x) { return x.count; }),
              backgroundColor: '#1e88ff' }]
          },
          options: { plugins: { legend: { display: false } } }
        });
      });
  }

  function refreshOpenFloating() {
    if (currentTab !== 'open') return;
    // Reload R-multiples for open trades; cheap enough
    loadList();
  }

  function syncFromPool() {
    fetch(API + '/pending-fills', { headers: authHeaders() })
      .then(function (r) { return r.ok ? r.json() : []; })
      .then(function (list) {
        if (!list.length) { alert('没有待同步的清仓记录'); return; }
        var msg = list.map(function (f) {
          return f.fillId + ': ' + f.stockCode + ' @ ' + f.price + ' (' + f.lots + '手)';
        }).join('\n');
        var pick = prompt('待同步清仓记录:\n' + msg + '\n\n输入要同步的 fillId:');
        if (!pick) return;
        fetch(API + '/sync-from-fill/' + pick, {
          method: 'POST', headers: authHeaders()
        }).then(function (r) {
          if (!r.ok) return r.text().then(function (t) { throw new Error(t); });
          window.jlReload();
        }).catch(function (e) { alert('同步失败: ' + e.message); });
      });
  }
})();
```

- [ ] **Step 2: Smoke test in browser**

Open `http://localhost:8080/gp/journal.html` (with login). Manually:
- Add a paper trade (海康 002415 入 35 损 33 目标 41 R:R=3 ✓)
- Tick all 5 red lines → save
- See the card appear in list
- Click "平仓" → enter price 41 → confirm
- Stats card shows 100% win rate; equity chart has 1 point

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/js/journal.js
git commit -m "feat(journal): trade list + close form + stats + Chart.js"
```

---

## Task 17: Frontend — header nav link

**Files:**
- Modify: `src/main/resources/static/header.html`

- [ ] **Step 1: Locate nav block**

```bash
grep -n "position-management\|monitor\.html\|invest\.html" src/main/resources/static/header.html | head -10
```

- [ ] **Step 2: Add `复盘` link**

Insert a new `<a>` (or `<li>`) near the existing entries pointing to `journal.html`. For example, after the `position-management` link:

```html
<a href="/gp/journal.html">复盘</a>
```

(Match the existing markup style — if nav is a `<ul>`, use `<li><a href="/gp/journal.html">复盘</a></li>`.)

- [ ] **Step 3: Verify**

```bash
mvn spring-boot:run &
sleep 25
curl -s http://localhost:8080/gp/header.html | grep -i "journal.html\|复盘"
kill %1
```

Expected: link present.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/static/header.html
git commit -m "feat(journal): add 复盘 nav link to header"
```

---

## Task 18: Full test run + smoke

- [ ] **Step 1: Run all unit tests**

```bash
mvn test -q 2>&1 | tail -20
```

Expected: All tests pass (existing + new). Confirm new `JournalServiceCreateTest`, `JournalServiceCloseTest`, `JournalServiceListTest`, `JournalStatsServiceTest`, `JournalControllerTest`, `JournalCronServiceTest` all PASS.

- [ ] **Step 2: Build production jar**

```bash
mvn clean package -DskipTests -q 2>&1 | tail -10
```

Expected: BUILD SUCCESS, `target/gupiao-quant-1.0.0.jar` exists.

- [ ] **Step 3: Deploy via restart.sh**

```bash
./restart.sh
```

Expected: process restarts, health check passes, `/gp/journal.html` returns 200.

- [ ] **Step 4: Manual smoke**

In a real browser at `https://aidaily.dpdns.org/gp/journal.html`:
1. Login
2. Click 复盘 in nav
3. Add a PAPER trade (002415, 35, 33, 41, 100 shares, all 5 red lines)
4. Verify list shows it
5. 平仓 at 41
6. Verify stats: 100% win rate, +3R average R, EV +3.00, equity curve goes up
7. Filter by PAPER — same numbers
8. Switch to ALL — same numbers
9. `tail -f app.log` — confirm no errors

- [ ] **Step 5: Commit any final tweaks**

```bash
git status
# If any fix-ups from smoke test, commit them
```

---

## Spec Coverage Check

| Spec Section | Task(s) |
|---|---|
| 1. Background & goals | (read by user) |
| 2. User decisions | (read by user) |
| 3. Data model | T1, T2, T3 |
| 4. Backend architecture — packages | T2, T3, T4, T5, T6, T7, T10 |
| 4. REST 10 endpoints | T10, T11 |
| 4. R-multiple / EV / drawdown / equity / R-distribution | T5, T6, T8, T9 |
| 4. Cron | T12 |
| 4. SchemaInitializer | T1, T5 |
| 5. Frontend HTML structure | T14 |
| 5. Three-column layout | T14 (CSS), T16 (JS) |
| 5. New trade form | T14 (HTML), T15 (JS) |
| 5. 5 red lines | T14, T15, T5 (backend hard checks) |
| 5. List + close form | T16 |
| 5. Stats panel + charts | T16 |
| 5. Reuse fetchCurrentPrice + Chart.js | T15 (fetchCurrentPrice logic inlined), T14 (Chart.js CDN), T16 (Chart.js usage) |
| 6. Tests | T5, T6, T7, T8, T9, T10, T12, T18 |
| 7. POOL_SYNC dedupe | T3 (unique key), T11 |
| 7. Soft delete | T6 |
| 7. last-write-wins | (out of scope per spec §7) |
| 7. mode filter | T7, T8, T16 |
| 7. Auto-close safety | T12 |
| 7. Dual validation (frontend + backend) | T5 (backend hard), T15 (frontend soft) |
| 8. Milestones | All 7 milestones covered by T1-T18 |
| 9. Reuse existing | T14 (CSS reuse), T15 (price fetch), T11 (InvestPositionFill), T12 (NotificationDispatcher) |

No spec gaps found.
