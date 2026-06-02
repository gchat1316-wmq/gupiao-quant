package com.quant.repository;

import com.quant.entity.ProsperityHotSector;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ProsperityHotSectorRepository extends JpaRepository<ProsperityHotSector, Integer> {

    List<ProsperityHotSector> findBySnapDateOrderByRankNoAsc(LocalDate snapDate);

    Optional<ProsperityHotSector> findBySnapDateAndSectorName(LocalDate snapDate, String sectorName);

    void deleteBySnapDate(LocalDate snapDate);

    Optional<ProsperityHotSector> findFirstByOrderBySnapDateDesc();
}
