package com.example.PhanTichAI.controller;

import com.example.PhanTichAI.entity.Strategy;
import com.example.PhanTichAI.repository.StrategyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminStrategyControllerTest {
    private StrategyRepository repository;
    private JdbcTemplate jdbc;
    private AdminStrategyController controller;
    private Strategy stored;

    @BeforeEach void setup() {
        repository=mock(StrategyRepository.class);jdbc=mock(JdbcTemplate.class);
        controller=new AdminStrategyController(repository,jdbc,new ObjectMapper());
        when(jdbc.queryForObject(contains("sp_getapplock"),eq(Integer.class),anyString())).thenReturn(0);
        when(jdbc.queryForObject(contains("OBJECT_ID"),eq(Integer.class))).thenReturn(0);
        when(jdbc.queryForList("SELECT segment_name FROM customer_segments WHERE id=?",String.class,0))
                .thenReturn(List.of("Khách hàng sắp ngủ đông"));
        stored=new Strategy();stored.setId(42);stored.setSegmentId(0);stored.setTitle("Ưu đãi đã duyệt");
        stored.setDiscountPercent(new BigDecimal("5"));stored.setUpdatedAt(LocalDateTime.of(2026,11,8,10,0));
        when(repository.findBySegmentId(0)).thenReturn(Optional.of(stored));
    }
    private AdminStrategyController.UpdateStrategyDto input() {
        var dto=new AdminStrategyController.UpdateStrategyDto();dto.setTitle("Chăm sóc khách hàng");
        dto.setDiscountPercent(new BigDecimal("6"));dto.setIsActive(true);dto.setExpectedStrategyId(42);
        dto.setExpectedUpdatedAt(stored.getUpdatedAt());return dto;
    }
    private void activeVersion(String version) {
        when(jdbc.queryForList("SELECT version,model_json FROM dbo.model_versions WHERE status='ACTIVE'"))
                .thenReturn(List.of(Map.of("version",version,"model_json","{\"clusters\":[{\"cluster_id\":0,\"monetary_spent\":125000}]}")));
    }
    @Test void knownCurrentSlotCanBeEditedWithoutChangingMembership() {
        activeVersion("v6");var dto=input();dto.setActiveVersion("v6");
        var response=controller.updateStrategy(0,dto);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(repository).save(stored);verify(repository,never()).delete(any());
        assertThat(stored.getSegmentId()).isZero();
        var body=(Map<?,?>)response.getBody();
        var view=(AdminStrategyController.StrategyView)body.get("data");
        assertThat(view.segmentName()).isEqualTo("Khách hàng sắp ngủ đông");
        assertThat(view.segmentRank()).isEqualTo(1);assertThat(view.activeVersion()).isEqualTo("v6");
        assertThat(view.monetarySpent()).isEqualByComparingTo("125000");
    }
    @Test void staleModelVersionCannotEditNewClusterWithSameNumber() {
        activeVersion("new-model");var dto=input();dto.setActiveVersion("old-model");
        assertThatThrownBy(()->controller.updateStrategy(0,dto)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("Phân cụm đã thay đổi");
        verify(repository,never()).save(any());
    }
    @Test void missingModelVersionCannotEditSynchronizedModel() {
        activeVersion("v6");
        assertThatThrownBy(()->controller.updateStrategy(0,input())).isInstanceOf(ResponseStatusException.class).hasMessageContaining("Phân cụm đã thay đổi");
        verify(repository,never()).save(any());
    }
    @Test void changingKDoesNotAllowOldStrategyIdToOverwriteReplacement() {
        var dto=input();dto.setExpectedStrategyId(17);
        assertThatThrownBy(()->controller.updateStrategy(0,dto)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("đã được thay thế");
        verify(repository,never()).save(any());
    }
    @Test void concurrentEditDoesNotOverwriteNewerPolicy() {
        var dto=input();dto.setExpectedUpdatedAt(stored.getUpdatedAt().minusMinutes(1));
        assertThatThrownBy(()->controller.updateStrategy(0,dto)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("người khác cập nhật");
        verify(repository,never()).save(any());
    }
    @Test void noRogueOrMissingStrategyCanBeCreatedThroughPut() {
        when(jdbc.queryForList("SELECT segment_name FROM customer_segments WHERE id=?",String.class,0)).thenReturn(List.of());
        assertThatThrownBy(()->controller.updateStrategy(0,input())).isInstanceOf(ResponseStatusException.class).hasMessageContaining("không còn");
        when(jdbc.queryForList("SELECT segment_name FROM customer_segments WHERE id=?",String.class,0)).thenReturn(List.of("Tên cụm"));
        when(repository.findBySegmentId(0)).thenReturn(Optional.empty());
        assertThatThrownBy(()->controller.updateStrategy(0,input())).isInstanceOf(ResponseStatusException.class).hasMessageContaining("đồng bộ");
        verify(repository,never()).save(any());
    }
    @Test void legacyPartialSyncAndDeletionCannotBreakExactClusterCount() {
        assertThatThrownBy(()->controller.syncKStrategies(6,List.of())).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->controller.cleanExcessStrategies(6)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->controller.deleteStrategy(0)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->controller.createStrategy(input())).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(repository);
    }
    @Test void aDeploymentLockTimeoutDoesNotWriteAnything() {
        when(jdbc.queryForObject(contains("sp_getapplock"),eq(Integer.class),anyString())).thenReturn(-1);
        assertThatThrownBy(()->controller.updateStrategy(0,input())).isInstanceOf(ResponseStatusException.class).hasMessageContaining("đang áp dụng");
        verify(repository,never()).save(any());
    }
    @Test void strategyReadReturnsUniqueStoredClusterNameAndActiveMetadata() throws Exception {
        activeVersion("active-six");
        ResultSet row=mock(ResultSet.class);when(row.getInt("id")).thenReturn(42);when(row.getObject("segment_id")).thenReturn(0);
        when(row.getString("title")).thenReturn("Chăm sóc khách");when(row.getString("segment_name")).thenReturn("Khách hàng ngủ đông · chi tiêu cao");
        when(row.getObject("segment_rank")).thenReturn(1);when(row.getObject("is_active")).thenReturn(true);
        when(row.getBigDecimal("discount_percent")).thenReturn(BigDecimal.ZERO);
        when(jdbc.query(contains("FROM strategies s LEFT JOIN"),any(RowMapper.class))).thenAnswer(invocation->{
            RowMapper<?> mapper=invocation.getArgument(1);return List.of(mapper.mapRow(row,0));
        });
        var result=controller.getAllStrategies().getBody();assertThat(result).hasSize(1);
        assertThat(result.get(0).segmentName()).isEqualTo("Khách hàng ngủ đông · chi tiêu cao");assertThat(result.get(0).activeVersion()).isEqualTo("active-six");
        verify(repository,never()).save(any());
    }
}
