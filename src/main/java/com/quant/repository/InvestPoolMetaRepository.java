package com.quant.repository;

import com.quant.entity.InvestPoolMeta;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InvestPoolMetaRepository extends JpaRepository<InvestPoolMeta, String> {

    List<InvestPoolMeta> findAllByOrderByDisplayOrderAscPoolTypeAsc();
}