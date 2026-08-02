package com.nageoffer.ai.ragent.infra.embedding;

import com.nageoffer.ai.ragent.infra.enums.ModelProvider;
import okhttp3.OkHttpClient;
import org.springframework.stereotype.Service;

@Service
public class GiteeEmbeddingClient extends AbstractOpenAIStyleEmbeddingClient {

    public GiteeEmbeddingClient(OkHttpClient syncHttpClient) {
        super(syncHttpClient);
    }

    @Override
    public String provider() {
        return ModelProvider.GITEE.getId();
    }

    @Override
    protected int maxBatchSize() {
        return 32;
    }
}
