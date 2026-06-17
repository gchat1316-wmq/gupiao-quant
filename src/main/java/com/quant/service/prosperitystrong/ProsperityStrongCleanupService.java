package com.quant.service.prosperitystrong;

import com.quant.repository.ProsperityHotSectorRepository;
import com.quant.repository.ProsperityLeaderCandidateRepository;
import com.quant.repository.ProsperityPickDailyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 流水线快照清理 — 独立短事务 (REQUIRES_NEW)。
 *
 * <p>背景: 原本 {@code deleteBySnapDate} 与后续 sector insert 共用同一事务,
 * Step1 中 30 个 sector 的 AI narrative 生成会把整个事务拖到分钟级,
 * 此时唯一键 {@code uk_date_sector(snap_date, sector_name)} 上的行锁一直持有,
 * 第二个流水线 (定时 + 手动并发触发) 在 insert 时会等锁, 命中 innodb_lock_wait_timeout。
 *
 * <p>把删除拆成独立事务后, 行锁在毫秒级 commit 时立刻释放, insert 阶段不再有
 * "同事务先 delete 再 insert" 的死锁窗口。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProsperityStrongCleanupService {

    private final ProsperityHotSectorRepository sectorRepo;
    private final ProsperityLeaderCandidateRepository leaderRepo;
    private final ProsperityPickDailyRepository pickRepo;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void clearSnapDate(LocalDate snapDate) {
        // 顺序: leaders -> picks -> sectors, 让外键(若日后加上) 由叶子向根删
        leaderRepo.deleteBySnapDate(snapDate);
        leaderRepo.flush();
        pickRepo.deleteBySnapDate(snapDate);
        pickRepo.flush();
        sectorRepo.deleteBySnapDate(snapDate);
        sectorRepo.flush();
        log.debug("已清理热点选股快照: snapDate={}", snapDate);
    }
}