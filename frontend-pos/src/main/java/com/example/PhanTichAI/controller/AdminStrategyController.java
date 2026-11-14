package com.example.PhanTichAI.controller;

import com.example.PhanTichAI.entity.Strategy;
import com.example.PhanTichAI.repository.StrategyRepository;
import com.example.PhanTichAI.service.CustomerLanguage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static org.springframework.http.HttpStatus.*;

@RestController
@RequestMapping("/api/admin/strategies")
public class AdminStrategyController {
    private final StrategyRepository strategies;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final com.example.PhanTichAI.service.ExperimentService experiments;

    public AdminStrategyController(StrategyRepository strategies, JdbcTemplate jdbc, ObjectMapper json) {
        this(strategies, jdbc, json, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AdminStrategyController(StrategyRepository strategies, JdbcTemplate jdbc, ObjectMapper json,
                                   com.example.PhanTichAI.service.ExperimentService experiments) {
        this.strategies = strategies; this.jdbc = jdbc; this.json = json; this.experiments = experiments;
    }

    public record StrategyView(Integer id, Integer segmentId, String title, BigDecimal discountPercent,
                               String giftDescription, String cashierScript, Boolean isActive,
                               LocalDateTime updatedAt, String segmentName, Integer segmentRank,
                               BigDecimal monetarySpent, String activeVersion) {}

    private record ActiveState(String version, JsonNode config) {}

    @GetMapping
    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public ResponseEntity<List<StrategyView>> getAllStrategies() {
        lockDeployment("Shared");
        ActiveState active = activeState();
        return ResponseEntity.ok(jdbc.query("""
                SELECT s.id,s.segment_id,s.title,s.discount_percent,s.gift_description,
                       s.cashier_script,s.is_active,s.updated_at,c.segment_name,
                       CASE WHEN c.id IS NULL THEN NULL ELSE c.id+1 END AS segment_rank
                FROM strategies s LEFT JOIN customer_segments c ON c.id=s.segment_id
                ORDER BY CASE WHEN c.id IS NULL THEN 1 ELSE 0 END,c.id,s.id
                """, (rs, row) -> {
            Strategy source = new Strategy();
            source.setId(rs.getInt("id")); source.setSegmentId((Integer) rs.getObject("segment_id"));
            source.setTitle(rs.getString("title")); source.setDiscountPercent(rs.getBigDecimal("discount_percent"));
            source.setGiftDescription(rs.getString("gift_description")); source.setCashierScript(rs.getString("cashier_script"));
            source.setIsActive((Boolean) rs.getObject("is_active"));
            var updated = rs.getTimestamp("updated_at"); source.setUpdatedAt(updated == null ? null : updated.toLocalDateTime());
            return present(source, rs.getString("segment_name"), (Integer) rs.getObject("segment_rank"), active);
        }));
    }

    @GetMapping("/{segmentId}")
    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public ResponseEntity<?> getStrategyBySegmentId(@PathVariable Integer segmentId) {
        lockDeployment("Shared");
        Strategy strategy = strategies.findBySegmentId(segmentId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Không tìm thấy chiến lược của phân khúc."));
        var names = jdbc.queryForList("SELECT segment_name FROM customer_segments WHERE id=?", String.class, segmentId);
        return ResponseEntity.ok(present(strategy, names.isEmpty() ? null : names.get(0), names.isEmpty() ? null : segmentId + 1, activeState()));
    }

    public static class UpdateStrategyDto {
        private Integer segmentId;
        private String title;
        private BigDecimal discountPercent;
        private String giftDescription;
        private String cashierScript;
        private Boolean isActive;
        private String activeVersion;
        private Integer expectedStrategyId;
        private LocalDateTime expectedUpdatedAt;

        public Integer getSegmentId() { return segmentId; }
        public void setSegmentId(Integer value) { segmentId = value; }
        public String getTitle() { return title; }
        public void setTitle(String value) { title = value; }
        public BigDecimal getDiscountPercent() { return discountPercent; }
        public void setDiscountPercent(BigDecimal value) { discountPercent = value; }
        public String getGiftDescription() { return giftDescription; }
        public void setGiftDescription(String value) { giftDescription = value; }
        public String getCashierScript() { return cashierScript; }
        public void setCashierScript(String value) { cashierScript = value; }
        public Boolean getIsActive() { return isActive; }
        public void setIsActive(Boolean value) { isActive = value; }
        public String getActiveVersion() { return activeVersion; }
        public void setActiveVersion(String value) { activeVersion = value; }
        public Integer getExpectedStrategyId() { return expectedStrategyId; }
        public void setExpectedStrategyId(Integer value) { expectedStrategyId = value; }
        public LocalDateTime getExpectedUpdatedAt() { return expectedUpdatedAt; }
        public void setExpectedUpdatedAt(LocalDateTime value) { expectedUpdatedAt = value; }
    }

    public ResponseEntity<?> updateStrategy(Integer segmentId, UpdateStrategyDto dto) { return updateStrategy(segmentId, dto, null); }

    @PutMapping("/{segmentId}")
    @Transactional
    public ResponseEntity<?> updateStrategy(@PathVariable Integer segmentId, @RequestBody UpdateStrategyDto dto,
                                            @org.springframework.security.core.annotation.AuthenticationPrincipal com.example.PhanTichAI.security.StoreUser user) {
        validate(dto);
        lockDeployment("Exclusive");
        ActiveState active = activeState();
        if (active.version() != null && !active.version().equals(dto.getActiveVersion()))
            throw new ResponseStatusException(CONFLICT, "Phân cụm đã thay đổi. Hãy tải lại danh sách ưu đãi trước khi lưu.");
        var names = jdbc.queryForList("SELECT segment_name FROM customer_segments WHERE id=?", String.class, segmentId);
        if (names.isEmpty()) throw new ResponseStatusException(CONFLICT, "Phân khúc không còn trong mô hình đang áp dụng. Hãy tải lại danh sách.");
        Strategy strategy = strategies.findBySegmentId(segmentId)
                .orElseThrow(() -> new ResponseStatusException(CONFLICT, "Chưa có chiến lược cho phân khúc. Hãy áp dụng lại phân cụm để đồng bộ."));
        if (dto.getExpectedStrategyId() != null && !dto.getExpectedStrategyId().equals(strategy.getId()))
            throw new ResponseStatusException(CONFLICT, "Chiến lược đã được thay thế khi đổi phân cụm. Hãy mở lại ưu đãi mới.");
        if (dto.getExpectedUpdatedAt() != null && !dto.getExpectedUpdatedAt().equals(strategy.getUpdatedAt()))
            throw new ResponseStatusException(CONFLICT, "Ưu đãi vừa được người khác cập nhật. Hãy tải lại trước khi lưu.");

        strategy.setTitle(CustomerLanguage.strategyTitle(dto.getTitle().trim()));
        strategy.setDiscountPercent(dto.getDiscountPercent());
        strategy.setGiftDescription(dto.getGiftDescription());
        strategy.setCashierScript(CustomerLanguage.cashierScript(dto.getCashierScript(), dto.getDiscountPercent(), dto.getGiftDescription()));
        boolean wasActive = Boolean.TRUE.equals(strategy.getIsActive());
        if (dto.getIsActive() != null) strategy.setIsActive(dto.getIsActive());
        strategy.setUpdatedAt(LocalDateTime.now());
        strategies.save(strategy);
        // Switching an offer on starts measuring it against a 10% control group; switching it off stops measuring.
        boolean nowActive = Boolean.TRUE.equals(strategy.getIsActive());
        if (experiments != null && !wasActive && nowActive)
            experiments.start(strategy, user == null ? "" : user.getFullName() + " (" + user.getUsername() + ")");
        if (experiments != null && wasActive && !nowActive) experiments.end(strategy.getId());
        return ResponseEntity.ok(Map.of("success", true, "message", "Đã lưu ưu đãi cho " + names.get(0),
                "data", present(strategy, names.get(0), segmentId + 1, active)));
    }

    // The deployment endpoint owns changes to model, assignments, segments, and strategy slots.
    @PostMapping("/sync-k")
    public ResponseEntity<?> syncKStrategies(@RequestParam Integer k, @RequestBody List<UpdateStrategyDto> ignored) { throw deploymentRequired(); }
    @PostMapping
    public ResponseEntity<?> createStrategy(@RequestBody UpdateStrategyDto ignored) { throw deploymentRequired(); }
    @DeleteMapping("/{segmentId}")
    public ResponseEntity<?> deleteStrategy(@PathVariable Integer segmentId) { throw deploymentRequired(); }
    @DeleteMapping("/clean-excess")
    public ResponseEntity<?> cleanExcessStrategies(@RequestParam Integer currentK) { throw deploymentRequired(); }

    private ResponseStatusException deploymentRequired() {
        return new ResponseStatusException(CONFLICT, "Dùng trang Mô hình phân cụm (xem trước rồi áp dụng) để đồng bộ đúng số phân khúc và chiến lược. Muốn dừng ưu đãi, hãy tắt trạng thái của chiến lược.");
    }
    private void lockDeployment(String mode) {
        Integer result = jdbc.queryForObject("""
                SET NOCOUNT ON;
                DECLARE @transaction_anchor int;
                SELECT TOP (1) @transaction_anchor=id FROM dbo.roles;
                DECLARE @result int;
                EXEC @result = sys.sp_getapplock @Resource=N'cosmetic-crm:cluster-model',
                    @LockMode=?,@LockOwner='Transaction',@LockTimeout=15000;
                SELECT @result;
                """, Integer.class, mode);
        jdbc.execute("SET NOCOUNT OFF");  // never leave NOCOUNT on a pooled connection (breaks Hibernate row counts)
        if (result == null || result < 0)
            throw new ResponseStatusException(CONFLICT, "Hệ thống đang áp dụng phân cụm. Vui lòng thử lưu lại sau.");
    }
    private ActiveState activeState() {
        var rows = jdbc.queryForList("SELECT version,model_json FROM dbo.model_versions WHERE status='ACTIVE'");
        if (rows.isEmpty()) return new ActiveState(null, json.createObjectNode());
        try { return new ActiveState((String) rows.get(0).get("version"), json.readTree((String) rows.get(0).get("model_json"))); }
        catch (Exception error) { throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Cấu hình phân cụm đang lưu chưa hợp lệ. Hãy kiểm tra hoặc áp dụng lại mô hình."); }
    }
    private StrategyView present(Strategy original, String name, Integer rank, ActiveState active) {
        Strategy view = CustomerLanguage.presentStrategy(original);
        JsonNode clusters = active.config().path("clusters");
        JsonNode cluster = clusters.isObject() ? clusters.path(String.valueOf(view.getSegmentId())) : json.createObjectNode();
        if (clusters.isArray()) {
            for (JsonNode candidate : clusters)
                if (Objects.equals(view.getSegmentId(), candidate.path("cluster_id").asInt(-1))) { cluster = candidate; break; }
        }
        BigDecimal monetary = cluster.path("monetary_spent").isNumber() ? cluster.path("monetary_spent").decimalValue() : null;
        return new StrategyView(view.getId(), view.getSegmentId(), view.getTitle(), view.getDiscountPercent(), view.getGiftDescription(),
                view.getCashierScript(), view.getIsActive(), view.getUpdatedAt(), name == null ? "Chưa đồng bộ phân khúc" : name,
                rank, monetary, active.version());
    }
    private void validate(UpdateStrategyDto dto) {
        if (dto.getTitle() == null || dto.getTitle().isBlank() || dto.getTitle().length() > 150 ||
                dto.getDiscountPercent() == null || dto.getDiscountPercent().signum() < 0 ||
                dto.getDiscountPercent().compareTo(new BigDecimal("100")) > 0 || dto.getDiscountPercent().scale() > 2 ||
                (dto.getGiftDescription() != null && dto.getGiftDescription().length() > 255) ||
                (dto.getCashierScript() != null && dto.getCashierScript().length() > 10000))
            throw new ResponseStatusException(BAD_REQUEST, "Tên, mức giảm giá hoặc mô tả chiến lược không hợp lệ.");
    }
}
