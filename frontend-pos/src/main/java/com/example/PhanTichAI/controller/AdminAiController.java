package com.example.PhanTichAI.controller;

import com.example.PhanTichAI.security.StoreUser;
import com.example.PhanTichAI.service.AiInternalKey;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Admin proxy to the analysis service: base model status, drift (step 7) and controlled retraining (step 8). */
@RestController
@RequestMapping("/api/admin/ai")
public class AdminAiController {
    private static final Set<String> SOURCES = Set.of("base", "extended");
    private final RestTemplate client;
    private final String baseUrl;
    private final ObjectMapper json;

    public AdminAiController(AiInternalKey key, @Value("${ai.base-url:http://localhost:8000}") String baseUrl, ObjectMapper json) {
        this.baseUrl = baseUrl;
        this.json = json;
        var factory = new SimpleClientHttpRequestFactory(); factory.setConnectTimeout(3000); factory.setReadTimeout(120000);
        client = new RestTemplate(factory);
        client.getInterceptors().add((request, body, execution) -> {
            request.getHeaders().set("X-Internal-Key", key.value()); return execution.execute(request, body);
        });
    }

    @GetMapping("/model-status") public ResponseEntity<String> status() { return forward("model-status", HttpMethod.GET, null); }
    @GetMapping("/active-clusters") public ResponseEntity<String> activeClusters() { return forward("active-clusters", HttpMethod.GET, null); }
    @GetMapping("/drift") public ResponseEntity<String> drift() { return forward("drift", HttpMethod.GET, null); }
    @PostMapping("/drift-check") public ResponseEntity<String> driftCheck() { return forward("drift-check", HttpMethod.POST, null); }
    @PostMapping("/rescore") public ResponseEntity<String> rescore() { return forward("rescore", HttpMethod.POST, null); }

    @GetMapping("/training-options") public ResponseEntity<String> options(@RequestParam(defaultValue = "base") String source) {
        checkSource(source);
        return forward("training-options?source=" + source, HttpMethod.GET, null);
    }

    /** Overview page: the base data split into k groups, to compare numbers of groups. Nothing is deployed. */
    @GetMapping("/base-analysis") public ResponseEntity<String> baseAnalysis(@RequestParam int k) {
        checkK(k);
        return forward("base-analysis?k=" + k, HttpMethod.GET, null);
    }

    @PostMapping("/train-preview") public ResponseEntity<String> preview(@RequestParam int k, @RequestParam(defaultValue = "base") String source) {
        checkK(k);
        checkSource(source);
        return forward(UriComponentsBuilder.fromPath("train-preview").queryParam("k", k).queryParam("source", source).toUriString(), HttpMethod.POST, null);
    }

    public record DeployRequest(String previewId, String expectedVersion, String reason) {}

    @PostMapping("/deploy-model")
    public ResponseEntity<String> deploy(@RequestBody DeployRequest request, @AuthenticationPrincipal StoreUser user) throws Exception {
        if (request == null || request.previewId() == null || !request.previewId().matches("[0-9a-f]{8,64}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Hãy xem trước mô hình trước khi áp dụng.");
        if (request.reason() != null && request.reason().length() > 500)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Lý do tối đa 500 ký tự.");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("preview_id", request.previewId());
        body.put("expected_version", request.expectedVersion());
        body.put("reason", request.reason() == null ? "" : request.reason().trim());
        body.put("user", user == null ? "" : user.getFullName() + " (" + user.getUsername() + ")");
        return forward("deploy-model", HttpMethod.POST, json.writeValueAsString(body));
    }

    @PostMapping("/chat-strategy") public ResponseEntity<String> chat(@RequestBody String body) { return forward("chat-strategy", HttpMethod.POST, body); }

    private void checkK(int k) {
        if (k < 2 || k > 10) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Số nhóm cần từ 2 đến 10.");
    }

    private void checkSource(String source) {
        if (!SOURCES.contains(source)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nguồn dữ liệu huấn luyện không hợp lệ.");
    }

    private ResponseEntity<String> forward(String path, HttpMethod method, String body) {
        var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            var response = client.exchange(URI.create(baseUrl + "/api/ai/" + path), method, new HttpEntity<>(body, headers), String.class);
            return ResponseEntity.status(response.getStatusCode()).contentType(MediaType.APPLICATION_JSON).body(response.getBody());
        } catch (HttpStatusCodeException ex) {
            return ResponseEntity.status(ex.getStatusCode()).contentType(MediaType.APPLICATION_JSON).body(ex.getResponseBodyAsString());
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Chưa kết nối được dịch vụ AI. Kiểm tra FastAPI và khóa nội bộ.");
        }
    }
}
