# Embedding model assets

The app bundles a sentence-embedding model for offline semantic product matching.
Two files must exist in this folder before building an APK that uses embeddings:

- `model.onnx`   — paraphrase-multilingual-MiniLM-L12-v2, ONNX (quantized), gitignored
- `tokenizer.json` — the matching tokenizer

## Fetch (run once, from this folder):

    curl -L -o model.onnx \
      "https://huggingface.co/onnx-models/paraphrase-multilingual-MiniLM-L12-v2-onnx/resolve/main/model.onnx"
    curl -L -o tokenizer.json \
      "https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2/resolve/main/tokenizer.json"

## Verify the output tensor name

`EmbeddingModel` calls `init(..., outputTensorName = "sentence_embedding")`. If your ONNX
export names its output differently, update that constant. Inspect with Netron
(https://netron.app) or:

    python -c "import onnx; m=onnx.load('model.onnx'); print([o.name for o in m.graph.output])"

If the app logs `Embedding model init failed`, matching silently falls back to
fuzzy-only — the app still works, just without semantic matching.
