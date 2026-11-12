package com.example.PhanTichAI.controller;

import com.example.PhanTichAI.entity.Product;
import com.example.PhanTichAI.repository.ProductRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;
import java.math.BigDecimal;
import java.util.*;

@RestController
@RequestMapping("/api/admin")
public class AdminCatalogController {
    private final JdbcTemplate jdbc;
    private final ProductRepository products;
    private final ObjectMapper json;
    public AdminCatalogController(JdbcTemplate jdbc, ProductRepository products, ObjectMapper json) { this.jdbc=jdbc; this.products=products; this.json=json; }
    /** Products are paged in SQL: the catalog keeps every historical item so past invoices stay linked. */
    @GetMapping("/products") public Map<String,Object> products(@RequestParam(defaultValue="") String search,
            @RequestParam(defaultValue="") String filter, @RequestParam(required=false) Integer categoryId,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="30") int size) {
        if (page<0 || page>1000000 || size<1 || size>200 || search.length()>150) throw new ResponseStatusException(BAD_REQUEST,"Bộ lọc không hợp lệ.");
        StringBuilder where=new StringBuilder(" WHERE 1=1"); List<Object> args=new ArrayList<>();
        switch (filter) {
            case "active" -> where.append(" AND p.is_active=1");
            case "low" -> where.append(" AND p.is_active=1 AND p.stock_quantity<=10");
            case "out" -> where.append(" AND p.is_active=1 AND p.stock_quantity<=0");
            case "inactive" -> where.append(" AND p.is_active=0");
            case "" -> { }
            default -> throw new ResponseStatusException(BAD_REQUEST,"Bộ lọc không hợp lệ.");
        }
        if (categoryId!=null) { where.append(" AND p.category_id=?"); args.add(categoryId); }
        if (!search.isBlank()) {
            String pattern="%"+search.trim().replace("~","~~").replace("%","~%").replace("_","~_").replace("[","~[")+"%";
            where.append(" AND (p.product_name LIKE ? ESCAPE '~' OR p.source_title LIKE ? ESCAPE '~' OR p.barcode LIKE ? ESCAPE '~' OR CAST(p.id AS varchar(20))=?)");
            args.add(pattern); args.add(pattern); args.add(pattern); args.add(search.trim());
        }
        Integer total=jdbc.queryForObject("SELECT COUNT(*) FROM products p"+where,Integer.class,args.toArray());
        List<Object> paged=new ArrayList<>(args); paged.add(page*size); paged.add(size);
        var items=jdbc.queryForList("""
            SELECT p.id,p.barcode,p.product_name AS productName,p.category_id AS categoryId,p.unit_price AS unitPrice,
            p.cost_price AS costPrice,p.stock_quantity AS stockQuantity,p.is_active AS isActive,
            p.source_title AS sourceTitle,p.image_url AS imageUrl FROM products p
            """+where+" ORDER BY p.is_active DESC,p.id OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",paged.toArray());
        return Map.of("total",total,"page",page,"size",size,"items",items);
    }
    @GetMapping("/categories") public List<Map<String,Object>> categories() { return jdbc.queryForList("SELECT id,name FROM categories ORDER BY name,id"); }
    public record ProductInput(@NotBlank @Size(max=255) String productName, @Size(max=50) String barcode,
            Integer categoryId, @NotNull @DecimalMin("0.01") @DecimalMax("9999999999.99") @Digits(integer=10,fraction=2) BigDecimal unitPrice,
            @DecimalMin("0") @DecimalMax("9999999999.99") @Digits(integer=10,fraction=2) BigDecimal costPrice, @NotNull Boolean isActive) {}
    @PutMapping("/products/{id}") @Transactional public Map<String,Boolean> update(@PathVariable int id, @Valid @RequestBody ProductInput input) {
        Product product = products.findByIdForUpdate(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND,"Không tìm thấy sản phẩm."));
        if (input.categoryId()!=null && jdbc.queryForObject("SELECT COUNT(*) FROM categories WHERE id=?",Integer.class,input.categoryId())==0)
            throw new ResponseStatusException(BAD_REQUEST,"Danh mục không tồn tại.");
        product.setProductName(input.productName().trim()); product.setBarcode(input.barcode()==null || input.barcode().isBlank() ? null : input.barcode().trim());
        product.setCategoryId(input.categoryId()); product.setUnitPrice(input.unitPrice()); product.setCostPrice(input.costPrice()); product.setIsActive(input.isActive());
        products.save(product);
        return Map.of("success",true);
    }
    public record CategoryInput(@NotBlank @Size(max=100) String name) {}
    @PostMapping("/categories") @Transactional public Map<String,Boolean> createCategory(@Valid @RequestBody CategoryInput input) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM categories WITH (UPDLOCK,HOLDLOCK) WHERE name=?",Integer.class,input.name().trim())>0)
            throw new ResponseStatusException(CONFLICT,"Danh mục đã tồn tại.");
        jdbc.update("INSERT INTO categories(name) VALUES(?)",input.name().trim()); return Map.of("success",true);
    }
    @GetMapping("/inventory") public Map<String,Object> inventory() {
        return Map.of("summary", jdbc.queryForMap("""
            SELECT COUNT(*) AS products,COALESCE(SUM(CASE WHEN is_active=1 THEN stock_quantity ELSE 0 END),0) AS units,
            COALESCE(SUM(CASE WHEN is_active=1 AND stock_quantity<=10 THEN 1 ELSE 0 END),0) AS lowStock,
            COALESCE(SUM(CASE WHEN is_active=1 AND stock_quantity<=0 THEN 1 ELSE 0 END),0) AS outOfStock FROM products
            """), "lowStock",jdbc.queryForList("SELECT id,product_name AS name,stock_quantity AS stock FROM products WHERE is_active=1 AND stock_quantity<=10 ORDER BY stock_quantity,id"));
    }
    @GetMapping("/customers") public Map<String,Object> customers(@RequestParam(defaultValue="") String search,
            @RequestParam(required=false) Integer segment, @RequestParam(defaultValue="0") int page) {
        if (page<0 || page>1000000 || search.length()>150) throw new ResponseStatusException(BAD_REQUEST,"Bộ lọc không hợp lệ.");
        String where=" WHERE (? IS NULL OR c.segment_id=?) AND (c.full_name LIKE ? ESCAPE '~' OR c.phone LIKE ? ESCAPE '~' OR c.customer_code LIKE ? ESCAPE '~' OR CAST(c.customer_id AS varchar(20))=?)";
        String pattern="%"+search.replace("~","~~").replace("%","~%").replace("_","~_").replace("[","~[")+"%";
        Object[] args={segment,segment,pattern,pattern,pattern,search};
        var total=jdbc.queryForObject("SELECT COUNT(*) FROM customers c"+where,Integer.class,args);
        List<Object> paged=new ArrayList<>(Arrays.asList(args)); paged.add(page*30);
        var rows=jdbc.queryForList("""
            SELECT c.customer_id AS id,c.customer_code AS code,c.full_name AS name,c.phone,c.segment_id AS segmentId,
            s.segment_name AS segmentName,c.last_purchase AS lastPurchase,
            c.total_orders AS rfmOrders,c.total_spent AS rfmSpent,c.first_purchase AS firstPurchase,c.segment_scored_at AS scoredAt,
            COALESCE(o.orders,0) AS recordedOrders,COALESCE(o.revenue,0) AS recordedRevenue
            FROM customers c LEFT JOIN customer_segments s ON s.id=c.segment_id
            LEFT JOIN (SELECT customer_id,COUNT(*) AS orders,SUM(final_amount) AS revenue FROM orders GROUP BY customer_id) o ON o.customer_id=c.customer_id
            """+where+" ORDER BY c.customer_id OFFSET ? ROWS FETCH NEXT 30 ROWS ONLY",paged.toArray());
        return Map.of("total",total,"page",page,"size",30,"items",rows);
    }
    @GetMapping("/segments") public List<Map<String,Object>> segments() { return jdbc.queryForList("SELECT id,segment_name AS name FROM customer_segments ORDER BY id"); }
    @GetMapping("/orders/{id}") public Map<String,Object> order(@PathVariable String id) {
        var orders=jdbc.queryForList("""
            SELECT o.*,u.full_name AS cashierName,c.full_name AS customerName FROM orders o
            LEFT JOIN users u ON u.id=o.cashier_id LEFT JOIN customers c ON c.customer_id=o.customer_id WHERE o.transaction_no=?
            """,id);
        if(orders.isEmpty()) throw new ResponseStatusException(NOT_FOUND,"Không tìm thấy hóa đơn.");
        Map<String,Object> response = new LinkedHashMap<>();
        response.put("order",orders.get(0));
        response.put("items",jdbc.queryForList("""
            SELECT i.product_id AS productId,p.product_name AS name,i.quantity,i.unit_price AS unitPrice,i.revenue
            FROM order_items i LEFT JOIN products p ON p.id=i.product_id WHERE i.transaction_no=? ORDER BY i.id
            """,id));
        response.put("appliedStrategy", historicalStrategy(orders.get(0).get("applied_strategy_id")));
        return response;
    }
    private Map<String,Object> historicalStrategy(Object strategyId) {
        if (!(strategyId instanceof Number)) return null;
        // The archived snapshot is preferred: a live strategy may have been edited since the sale.
        Integer exists=jdbc.queryForObject("SELECT CASE WHEN OBJECT_ID(N'dbo.strategy_history',N'U') IS NULL THEN 0 ELSE 1 END",Integer.class);
        if (Integer.valueOf(1).equals(exists)) {
            var snapshots=jdbc.queryForList("SELECT snapshot_json FROM dbo.strategy_history WHERE original_strategy_id=?",String.class,strategyId);
            if(!snapshots.isEmpty()) {
                try {
                    var snapshot=json.readValue(snapshots.get(0),new TypeReference<Map<String,Object>>(){});
                    snapshot.put("archived",true);return snapshot;
                } catch (Exception error) {
                    throw new ResponseStatusException(SERVICE_UNAVAILABLE,"Chưa đọc được bản lưu ưu đãi của hóa đơn.");
                }
            }
        }
        var current=jdbc.queryForList("SELECT id,segment_id,title,discount_percent,gift_description,cashier_script,is_active,updated_at FROM strategies WHERE id=?",strategyId);
        if(current.isEmpty())return null;
        Map<String,Object> strategy=new LinkedHashMap<>(current.get(0));strategy.put("archived",false);return strategy;
    }
}
