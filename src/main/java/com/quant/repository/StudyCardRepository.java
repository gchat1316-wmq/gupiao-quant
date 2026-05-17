package com.quant.repository;

import com.quant.entity.StudyCard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StudyCardRepository extends JpaRepository<StudyCard, Long> {
    List<StudyCard> findByNodeIdAndCardTypeOrderBySortAscIdAsc(Long nodeId, String cardType);
    List<StudyCard> findByNodeIdOrderBySortAscIdAsc(Long nodeId);
}
