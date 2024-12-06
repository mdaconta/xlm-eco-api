package us.daconta.xlmeco.provider;

import org.json.JSONObject;

import java.util.List;

/**
 * Represents the result of a vector search operation.
 */
public class VectorSearchResult {
    private final String id;
    private final float score;
    private final List<Float> embedding;
    private final String content;
    private JSONObject metadata; // JSON blob

    public VectorSearchResult(String id, float score, List<Float> embedding, String content, String metadata) {
        this.id = id;
        this.score = score;
        this.embedding = embedding;
        this.content = content;
        if (metadata != null && !metadata.isEmpty()) {
            this.metadata = new JSONObject(metadata);
        }
    }

    public String getId() {
        return id;
    }

    public float getScore() {
        return score;
    }
    
    public List<Float> getEmbedding() {
        return embedding;
    }

    public String getContent() {
        return content;
    }

    public JSONObject getMetadata() {
        return metadata;
    }
}
