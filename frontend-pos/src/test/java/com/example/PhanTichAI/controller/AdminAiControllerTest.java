package com.example.PhanTichAI.controller;

import com.example.PhanTichAI.security.StoreUser;
import com.example.PhanTichAI.service.AiInternalKey;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class AdminAiControllerTest {
    private AdminAiController controller;
    private MockRestServiceServer server;
    private final StoreUser admin = new StoreUser(1, "admin", "x", "Quản trị", "ADMIN", true);
    @BeforeEach void setup() {
        var key=mock(AiInternalKey.class);when(key.value()).thenReturn("test-internal-key");
        controller=new AdminAiController(key,"http://localhost:8000",new ObjectMapper());
        server=MockRestServiceServer.bindTo((RestTemplate)ReflectionTestUtils.getField(controller,"client")).build();
    }
    @Test void statusReadsThroughAuthenticatedInternalProxy() {
        server.expect(requestTo("http://localhost:8000/api/ai/model-status")).andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Key","test-internal-key"))
                .andRespond(withSuccess("{\"active_k\":3,\"label\":\"v1\"}",MediaType.APPLICATION_JSON));
        assertThat(controller.status().getBody()).contains("\"label\":\"v1\"");server.verify();
    }
    @Test void deploySendsOnlyThePreviewIdExpectedVersionReasonAndUser() throws Exception {
        server.expect(requestTo("http://localhost:8000/api/ai/deploy-model")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.preview_id").value("abcdef12"))
                .andExpect(jsonPath("$.expected_version").value("v&k=2"))
                .andExpect(jsonPath("$.reason").value("Lệch dữ liệu"))
                .andExpect(jsonPath("$.user").value("Quản trị (admin)"))
                .andRespond(withSuccess("{\"success\":true}",MediaType.APPLICATION_JSON));
        var response=controller.deploy(new AdminAiController.DeployRequest("abcdef12","v&k=2"," Lệch dữ liệu "),admin);
        assertThat(response.getStatusCode().value()).isEqualTo(200);server.verify();
    }
    @Test void backendVersionConflictsReachTheBrowser() throws Exception {
        server.expect(requestTo("http://localhost:8000/api/ai/deploy-model"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON).body("{\"detail\":\"model changed\"}"));
        var response=controller.deploy(new AdminAiController.DeployRequest("abcdef12","old",""),admin);
        assertThat(response.getStatusCode().value()).isEqualTo(409);server.verify();
    }
    @Test void previewParametersAreValidatedBeforeCallingAi() {
        assertThatThrownBy(()->controller.preview(11,"base")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->controller.preview(3,"everything")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->controller.options("base&k=2")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->controller.deploy(new AdminAiController.DeployRequest("../x",null,null),admin)).isInstanceOf(ResponseStatusException.class);
        server.verify();
    }
    @Test void baseAnalysisForwardsOnlyAValidK() {
        assertThatThrownBy(()->controller.baseAnalysis(1)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->controller.baseAnalysis(11)).isInstanceOf(ResponseStatusException.class);
        server.expect(requestTo("http://localhost:8000/api/ai/base-analysis?k=5")).andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Key","test-internal-key"))
                .andRespond(withSuccess("{\"k\":5}",MediaType.APPLICATION_JSON));
        assertThat(controller.baseAnalysis(5).getBody()).contains("\"k\":5");server.verify();
    }
    @Test void previewForwardsKAndSource() {
        server.expect(requestTo("http://localhost:8000/api/ai/train-preview?k=4&source=extended")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"k\":4}",MediaType.APPLICATION_JSON));
        assertThat(controller.preview(4,"extended").getStatusCode().value()).isEqualTo(200);server.verify();
    }
}
