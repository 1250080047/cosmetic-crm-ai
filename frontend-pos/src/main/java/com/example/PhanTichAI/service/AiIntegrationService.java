package com.example.PhanTichAI.service;

import com.example.PhanTichAI.dto.AiPredictResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.util.Map;

@Service
public class AiIntegrationService {
    private final RestTemplate restTemplate;
    private final String predictUrl;

    public AiIntegrationService(@Value("${ai.base-url:http://localhost:8000}") String baseUrl, AiInternalKey internalKey) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(12000);
        this.restTemplate = new RestTemplate(factory);
        this.restTemplate.getInterceptors().add((request, body, execution) -> {
            request.getHeaders().set("X-Internal-Key", internalKey.value());
            return execution.execute(request, body);
        });
        this.predictUrl = baseUrl + "/api/ai/predict";
    }

    /** Places one customer's L, R, F, M on the stored ruler of the active model (no learning happens here). */
    public AiPredictResponse predictSegment(double length, double recency, double frequency, double monetary, boolean hasPurchase) {
        try {
            AiPredictResponse response = restTemplate.postForObject(predictUrl,
                    Map.of("length", length, "recency", recency, "frequency", frequency, "monetary", monetary,
                            "has_purchase", hasPurchase), AiPredictResponse.class);
            if (response != null) return response;
        } catch (Exception error) {
            System.err.println("Chưa kết nối được dịch vụ phân tích AI: " + error.getClass().getSimpleName());
        }
        AiPredictResponse unavailable = new AiPredictResponse();
        unavailable.setCluster_id(-100);
        unavailable.setSegment_name("Chưa xác định");
        unavailable.setColor_code("#64748b");
        unavailable.setDiscount_percent(0);
        unavailable.setGift_description("");
        unavailable.setCashier_script("Xin chào anh/chị. Em có thể hỗ trợ anh/chị tìm sản phẩm phù hợp không ạ?");
        return unavailable;
    }
}
