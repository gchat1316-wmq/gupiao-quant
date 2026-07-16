package com.quant.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.quant.entity.StudyCard;

public interface StudyCardRepository extends JpaRepository<StudyCard, Long> {
  List<StudyCard> findByNodeIdAndCardTypeOrderBySortAscIdAsc(Long nodeId, String cardType);

  List<StudyCard> findByNodeIdOrderBySortAscIdAsc(Long nodeId);
}
