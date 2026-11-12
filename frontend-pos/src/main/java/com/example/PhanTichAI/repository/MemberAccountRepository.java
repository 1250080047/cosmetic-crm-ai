package com.example.PhanTichAI.repository;

import com.example.PhanTichAI.entity.MemberAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MemberAccountRepository extends JpaRepository<MemberAccount, Integer> {
    /** Locks the account row so two wrong PINs typed at the same time are both counted. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from MemberAccount a where a.customerId = :id")
    Optional<MemberAccount> findByIdForUpdate(@Param("id") Integer id);
}
