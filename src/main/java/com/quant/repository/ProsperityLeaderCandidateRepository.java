package com.quant.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.quant.entity.ProsperityLeaderCandidate;

public interface ProsperityLeaderCandidateRepository
    extends JpaRepository<ProsperityLeaderCandidate, Integer> {

  List<ProsperityLeaderCandidate> findBySnapDateOrderByLeaderScoreDesc(LocalDate snapDate);

  List<ProsperityLeaderCandidate> findBySnapDateAndSectorIdOrderByLeaderScoreDesc(
      LocalDate snapDate, Integer sectorId);

  void deleteBySnapDate(LocalDate snapDate);
}
