package io.github.jasperzxy.javamanus.volcengine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import com.volcengine.ark.runtime.model.multimodalembeddings.MultimodalEmbeddingInput;
import com.volcengine.ark.runtime.model.multimodalembeddings.MultimodalEmbeddingRequest;
import com.volcengine.ark.runtime.service.ArkService;

import lombok.extern.slf4j.Slf4j;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;

@Slf4j
public class ArkEmbeddingModel implements EmbeddingModel {

    private final ArkProperties props;
    private final ArkService service;

    public ArkEmbeddingModel(ArkProperties props) {
        ConnectionPool connectionPool = new ConnectionPool(5, 1, TimeUnit.SECONDS);
        Dispatcher dispatcher = new Dispatcher();
        this.service = ArkService.builder()
                .dispatcher(dispatcher)
                .connectionPool(connectionPool)
                .apiKey(props.getApiKey())
                .build();
        this.props = props;
    }

    public void destroy() {
        service.shutdownExecutor();
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> list = new ArrayList<>(request.getInstructions().size());

        log.debug("embedding call, input count: {}", request.getInstructions().size());

        for (int i = 0; i < request.getInstructions().size(); i++) {
            var ds = embed0(request.getInstructions().get(i));
            float[] floats = new float[ds.size()];
            for (int j = 0; j < ds.size(); j++) {
                floats[j] = ds.get(j).floatValue();
            }

            log.trace("embedding result len:{}, first100:{}", floats.length,
                    Arrays.toString(floats).substring(0, Math.min(100, Arrays.toString(floats).length())));
            list.add(new Embedding(floats, i));
        }

        return new EmbeddingResponse(list);
    }

    @Override
    public float[] embed(Document document) {
        var ds = embed0(document.getText());
        float[] floats = new float[ds.size()];
        for (int i = 0; i < ds.size(); i++) {
            floats[i] = ds.get(i).floatValue();
        }
        return floats;
    }

    public List<Double> embed0(List<String> texts) {
        List<MultimodalEmbeddingInput> inputForReqList = new ArrayList<>();
        for (String text : texts) {
            MultimodalEmbeddingInput element = new MultimodalEmbeddingInput();
            element.setType("text");
            element.setText(text);
            inputForReqList.add(element);
        }

        MultimodalEmbeddingRequest req = MultimodalEmbeddingRequest.builder()
                .model(props.getEmbeddingModel())
                .dimensions(props.getDimensions())
                .input(inputForReqList)
                .encodingFormat("float")
                .build();

        return service.createMultiModalEmbeddings(req).getData().getEmbedding();
    }

    public List<Double> embed0(String text) {
        return embed0(List.of(text));
    }

}
