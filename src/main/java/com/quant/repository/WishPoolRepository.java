package com.quant.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.quant.entity.WishPool;

@Repository
public interface WishPoolRepository extends JpaRepository<WishPool, Long> {

  /** 后台列表（按创建时间倒序） */
  @Query(
      """
        SELECT w FROM WishPool w
         WHERE (:status IS NULL OR w.status = :status)
           AND (:keyword IS NULL
                OR LOWER(w.wish)  LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(w.email) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(w.reply) LIKE LOWER(CONCAT('%', :keyword, '%')))
         ORDER BY w.createdAt DESC
        """)
  Page<WishPool> adminSearch(
      @Param("status") WishPool.Status status, @Param("keyword") String keyword, Pageable pageable);

  /** 公开轮播：已 display=true 且有回复的，按回复时间倒序 */
  @Query(
      """
        SELECT w FROM WishPool w
         WHERE w.displayFlag = true
           AND w.reply IS NOT NULL
           AND w.reply <> ''
         ORDER BY w.replyAt DESC
        """)
  List<WishPool> findPublicDisplay(Pageable pageable);

  /** 各状态计数（后台看板用） */
  long countByStatus(WishPool.Status status);
}
