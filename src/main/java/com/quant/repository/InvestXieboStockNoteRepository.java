package com.quant.repository;

import com.quant.entity.InvestXieboStockNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface InvestXieboStockNoteRepository
        extends JpaRepository<InvestXieboStockNote, String> {
}
