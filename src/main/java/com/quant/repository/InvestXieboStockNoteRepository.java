package com.quant.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.quant.entity.InvestXieboStockNote;

@Repository
public interface InvestXieboStockNoteRepository
    extends JpaRepository<InvestXieboStockNote, String> {}
