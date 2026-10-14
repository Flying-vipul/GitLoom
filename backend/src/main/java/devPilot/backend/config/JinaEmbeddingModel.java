package devPilot.backend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Custom EmbeddingModel that calls Jina AI directly via plain REST.
 * Bypasses Spring AI OpenAI client which requires prompt_tokens in response.
 */
@Slf4j
@Primary
@Component("jinaEmbeddingModel")
public class JinaEmbeddingModel implements EmbeddingModel {

    private static final String JINA_URL = "https://api.jina.ai/v1/embeddings";

    @Value("${spring.ai.openai.embedding.api-key}")
    private String apiKey;

    @Value("${spring.ai.openai.embedding.options.model:jina-embeddings-v5-omni-small}")
    private String model;

    @Value("${spring.ai.vectorstore.pgvector.dimensions:1024}")
    private int dims;

    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    @SuppressWarnings("unchecked")
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> texts = request.getInstructions();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", texts);

        log.debug("Jina: embedding {} chunk(s) with [{}]", texts.size(), model);

        ResponseEntity<Map> response = restTemplate.exchange(
                JINA_URL, HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);

        Map<String, Object> responseBody = response.getBody();
        if (responseBody == null || !responseBody.containsKey("data")) {
            throw new IllegalStateException("Jina response missing 'data'. Body: " + responseBody);
        }

        List<Map<String, Object>> data = (List<Map<String, Object>>) responseBody.get("data");
        List<Embedding> embeddings = new ArrayList<>(data.size());

        for (Map<String, Object> item : data) {
            List<Number> vec = (List<Number>) item.get("embedding");
            float[] floats = new float[vec.size()];
            for (int i = 0; i < vec.size(); i++) {
                floats[i] = vec.get(i).floatValue();
            }
            int index = (item.get("index") instanceof Number n) ? n.intValue() : embeddings.size();
            embeddings.add(new Embedding(floats, index));
        }

        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getFormattedContent());
    }

    @Override
    public float[] embed(String text) {
        return call(new EmbeddingRequest(List.of(text), null))
                .getResult()
                .getOutput();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        return call(new EmbeddingRequest(texts, null))
                .getResults()
                .stream()
                .map(Embedding::getOutput)
                .toList();
    }

    @Override
    public int dimensions() {
        return dims;
    }
}