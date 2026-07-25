package com.quant.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.quant.entity.SwingEvent;

public interface SwingEventRepository extends JpaRepository<SwingEvent, Long> {

  List<SwingEvent> findTop50ByWatchIdOrderByCreatedAtDesc(Long watchId);
}
