package us.daconta.xlmeco.provider;

import org.eclipse.jetty.util.ajax.JSON;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;

/**
 * Represents a vector record containing embeddings, content, and metadata.
 */
public class VectorRecord {
    private final String id;
    private final List<Float> embedding;
    private final String content;
    private JSONObject metadata; // JSON blob

    public VectorRecord(String id, List<Float> embedding, String content, String metadata) {
        this.id = id;
        this.embedding = embedding;
        this.content = content;
        if (metadata != null && !metadata.isEmpty()) {
            this.metadata = new JSONObject(metadata);
        }
    }

    public String getId() {
        return id;
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

