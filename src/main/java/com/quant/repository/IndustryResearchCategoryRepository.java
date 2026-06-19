package com.quant.repository;

import com.quant.entity.IndustryResearchCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface IndustryResearchCategoryRepository extends JpaRepository<IndustryResearchCategory, Long> {

    List<IndustryResearchCategory> findByEnabledOrderBySortOrderAsc(Integer enabled);

    Optional<IndustryResearchCategory> findByCode(String code);
}