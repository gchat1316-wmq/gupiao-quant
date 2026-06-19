package com.quant.repository;

import com.quant.entity.IndustryResearchSection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface IndustryResearchSectionRepository extends JpaRepository<IndustryResearchSection, Long> {

    List<IndustryResearchSection> findByArticleIdOrderBySectionOrderAsc(Long articleId);

    Optional<IndustryResearchSection> findByArticleIdAndSectionKey(Long articleId, String sectionKey);

    void deleteByArticleId(Long articleId);
}