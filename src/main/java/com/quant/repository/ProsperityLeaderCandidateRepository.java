package com.quant.repository;

import com.quant.entity.ProsperityLeaderCandidate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface ProsperityLeaderCandidateRepository extends JpaRepository<ProsperityLeaderCandidate, Integer> {

    List<ProsperityLeaderCandidate> findBySnapDateOrderByLeaderScoreDesc(LocalDate snapDate);

    List<ProsperityLeaderCandidate> findBySnapDateAndSectorIdOrderByLeaderScoreDesc(LocalDate snapDate, Integer sectorId);

    void deleteBySnapDate(LocalDate snapDate);
}
