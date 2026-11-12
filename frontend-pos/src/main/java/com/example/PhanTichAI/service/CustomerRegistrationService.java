package com.example.PhanTichAI.service;

import com.example.PhanTichAI.entity.Customer;
import com.example.PhanTichAI.repository.CustomerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/** Creates a new customer record; used by the counter ("+ Mới") and by the self-service member page. */
@Service
public class CustomerRegistrationService {
    public static final Pattern PHONE = Pattern.compile("\\+?[0-9]{8,15}");
    private final CustomerRepository customers;

    public CustomerRegistrationService(CustomerRepository customers) { this.customers = customers; }

    public static boolean validPhone(String phone) { return phone != null && PHONE.matcher(phone.trim()).matches(); }

    /** The caller has checked that the phone number is free. The customer stays outside the groups until the first invoice. */
    @Transactional
    public Customer register(String phone, String fullName) {
        Integer maxId = customers.maxCustomerId();
        int newId = (maxId == null ? 0 : maxId) + 1;
        Customer customer = new Customer();
        customer.setCustomerId(newId);
        customer.setCustomerCode(String.format("KH%05d", newId));
        customer.setPhone(phone.trim());
        customer.setFullName(fullName != null && !fullName.trim().isEmpty() ? fullName.trim() : "Khách hàng mới");
        customer.setTotalOrders(0);
        customer.setTotalSpent(BigDecimal.ZERO);
        customer.setSegmentId(null);  // "Chưa mua hàng": outside the groups until the first invoice
        customers.save(customer);
        return customer;
    }
}
