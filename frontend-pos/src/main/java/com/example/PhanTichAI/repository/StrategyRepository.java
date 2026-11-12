package com.example.PhanTichAI.repository;

import com.example.PhanTichAI.entity.Strategy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface StrategyRepository extends JpaRepository<Strategy, Integer> {
    Optional<Strategy> findBySegmentId(Integer segmentId);
}