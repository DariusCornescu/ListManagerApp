# Embedding model assets

The app bundles a sentence-embedding model for offline semantic product matching.
Two files must exist in this folder before building an APK that uses embeddings
(both are gitignored):

- `model.onnx`   — paraphrase-multilingual-MiniLM-L12-v2, **int8-quantized** ONNX (~118 MB)
- `tokenizer.json` — the matching tokenizer

We use the quantized export (~118 MB, mobile-appropriate), NOT the 470 MB fp32 model.

## Fetch (run once, from this folder):

    curl -L -o model.onnx \
      "https://huggingface.co/Xenova/paraphrase-multilingual-MiniLM-L12-v2/resolve/main/onnx/model_quantized.onnx"
    curl -L -o tokenizer.json \
      "https://huggingface.co/Xenova/paraphrase-multilingual-MiniLM-L12-v2/resolve/main/tokenizer.json"

## Model I/O (already wired in `EmbeddingModel`)

This export has three required inputs (`input_ids`, `attention_mask`, `token_type_ids`)
and a single 3D output `last_hidden_state [batch, seq, 384]`. The `sentence-embeddings`
library reads output 0, mean-pools it with the attention mask, and we L2-normalize the
result — so `EmbeddingModel.init(...)` sets `useTokenTypeIds = true` and `outputTensorName`
is ignored by the library (kept accurate as `last_hidden_state`). Inspect a different
export with Netron (https://netron.app) or:

    python -c "import onnx; m=onnx.load('model.onnx'); print([i.name for i in m.graph.input],[o.name for o in m.graph.output])"

If the app logs `Embedding model init failed`, matching silently falls back to
fuzzy-only — the app still works, just without semantic matching.
