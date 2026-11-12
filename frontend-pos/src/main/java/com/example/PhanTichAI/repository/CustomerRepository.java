package com.example.PhanTichAI.repository;

import com.example.PhanTichAI.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, Integer> {
    // Khai báo hàm tìm khách hàng theo số điện thoại
    Optional<Customer> findByPhone(String phone);
    Optional<Customer> findByCustomerCodeIgnoreCase(String customerCode);
    @org.springframework.data.jpa.repository.Query("select coalesce(max(c.customerId), 0) from Customer c")
    Integer maxCustomerId();
}