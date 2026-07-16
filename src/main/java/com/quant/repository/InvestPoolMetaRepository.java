package com.quant.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.quant.entity.InvestPoolMeta;

public interface InvestPoolMetaRepository extends JpaRepository<InvestPoolMeta, String> {

  List<InvestPoolMeta> findAllByOrderByDisplayOrderAscPoolTypeAsc();
}
