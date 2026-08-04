package com.quant.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.quant.entity.EtfNavSnapshot;

public interface EtfNavSnapshotRepository extends JpaRepository<EtfNavSnapshot, Long> {

  Optional<EtfNavSnapshot> findBySnapDate(LocalDate snapDate);

  List<EtfNavSnapshot> findBySnapDateGreaterThanEqualOrderBySnapDateAsc(LocalDate fromDate);
}
