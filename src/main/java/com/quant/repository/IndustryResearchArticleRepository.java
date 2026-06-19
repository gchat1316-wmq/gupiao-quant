package com.quant.repository;

import com.quant.entity.IndustryResearchArticle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface IndustryResearchArticleRepository extends JpaRepository<IndustryResearchArticle, Long> {

    Optional<IndustryResearchArticle> findBySlug(String slug);

    List<IndustryResearchArticle> findByCategoryIdAndStatusOrderByUpdatedAtDesc(Long categoryId, String status);

    @Query("SELECT a FROM IndustryResearchArticle a WHERE a.categoryId = :cid AND a.status = 'published' ORDER BY a.updatedAt DESC")
    List<IndustryResearchArticle> findPublishedByCategory(@Param("cid") Long categoryId);

    @Query("SELECT a FROM IndustryResearchArticle a WHERE a.status = 'published' ORDER BY a.updatedAt DESC")
    List<IndustryResearchArticle> findAllPublished();
}