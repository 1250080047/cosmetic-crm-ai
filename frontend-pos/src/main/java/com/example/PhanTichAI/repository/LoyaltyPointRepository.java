package com.example.PhanTichAI.repository;

import com.example.PhanTichAI.entity.LoyaltyPoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LoyaltyPointRepository extends JpaRepository<LoyaltyPoint, Long> {
    @Query("select coalesce(sum(p.points), 0) from LoyaltyPoint p where p.customerId = :customerId")
    Long balanceOf(@Param("customerId") Integer customerId);

    List<LoyaltyPoint> findTop10ByCustomerIdOrderByCreatedAtDescIdDesc(Integer customerId);
}
