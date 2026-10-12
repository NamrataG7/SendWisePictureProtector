# Model Card — NSFW / Nudity Image Classifier

## Status: bundled ✅

`nsfw_classifier.tflite` (17 MB) **is bundled** in this repo at
`app/src/main/assets/models/nsfw_classifier.tflite`.

- **Source**: [GantMan/nsfw_model](https://github.com/GantMan/nsfw_model), release
  [`1.2.0`](https://github.com/GantMan/nsfw_model/releases/tag/1.2.0),
  asset `mobilenet_v2_140_224.1.zip` → `mobilenet_v2_140_224/saved_model.tflite`
  (already shipped pre-converted to TFLite by the upstream project — no
  local conversion was needed).
- **License**: MIT (`Copyright (c) 2020 The nsfw_model Developers`) — see
  `THIRD_PARTY_LICENSES/nsfw_model-LICENSE.md` at the repo root. Permissive,
  commercial-safe, redistribution of the binary is explicitly permitted.
- **Architecture**: MobileNetV2 (width multiplier 1.4), 224×224 input,
  5-class softmax output.
- **Reported accuracy** (from upstream `training_results.txt`): ~91.9%
  validation accuracy after 5 epochs on the upstream training run.
- **Verified tensor shapes** (inspected directly via the TFLite flatbuffer
  schema against this exact bundled file — not assumed):
  - Input tensor `input`: `[1, 224, 224, 3]`, `float32`
  - Output tensor `Identity`: `[1, 5]`, `float32`
  - Confirmed class order via upstream `class_labels.txt`:
    `drawings, hentai, neutral, porn, sexy` — matches `NsfwImageClassifier.LABELS` exactly.

If this asset is ever removed/corrupted, `NsfwImageClassifier` **fails
open**: it logs a warning via `android.util.Log.w("NsfwImageClassifier", ...)`
and returns

```kotlin
ClassificationResult(isSensitive = false, confidence = 0f, label = "unavailable")
```

i.e. it treats every image as non-sensitive and never blocks the user — this
mirrors the fail-open philosophy already used by `ToxicityAnalyzer` /
`EnhancedToxicityAnalyzer` for text, and means a bad/missing model asset
degrades gracefully instead of crashing the keyboard.

## Re-generating / replacing this file

If you want to retrain or swap in a different NSFW classifier:

```bash
# Option A: re-fetch the same upstream artifact used here
curl -L -o model.zip \
  https://github.com/GantMan/nsfw_model/releases/download/1.2.0/mobilenet_v2_140_224.1.zip
unzip model.zip
cp mobilenet_v2_140_224/saved_model.tflite \
  app/src/main/assets/models/nsfw_classifier.tflite

# Option B: convert your own Keras/SavedModel weights
python3 -c "
import tensorflow as tf
model = tf.keras.models.load_model('your_model.h5')
converter = tf.lite.TFLiteConverter.from_keras_model(model)
open('nsfw_classifier.tflite', 'wb').write(converter.convert())
"
```

| Tensor | Shape | Dtype | Notes |
|---|---|---|---|
| Input | `[1, 224, 224, 3]` | `float32` | NHWC, RGB, values normalized to `[0, 1]` (pixel / 255.0) |
| Output | `[1, 5]` | `float32` | Softmax probabilities over 5 classes |

## Expected output class order

Index order expected by `NsfwImageClassifier.LABELS`:

| Index | Label |
|---|---|
| 0 | `drawings` |
| 1 | `hentai` |
| 2 | `neutral` |
| 3 | `porn` |
| 4 | `sexy` |

This matches the GantMan/nsfw_model class ordering. If a different model /
training run is used, make sure the output tensor's class order is
remapped to match (or update `NsfwImageClassifier.LABELS` and the index
lookups in `postprocess()` to match the new model).

## Decision logic (already implemented in code, documented here for reference)

- `isSensitive = true` when `porn + hentai + sexy` probability mass is
  `>= sensitiveThreshold` (default `0.5`, configurable per-call via the
  `classify(bitmap, sensitiveThreshold)` parameter).
- `label` = the single highest-probability class name (could be `neutral`
  or `drawings` even when `isSensitive` is true, if e.g. `porn` + `hentai`
  + `sexy` collectively exceed threshold but no single one of them is the
  argmax — this is intentional: `isSensitive` and `label` answer two
  different questions).
- On any failure: `isSensitive = false`, `confidence = 0f`,
  `label = "unavailable"` — fail open, never block the user.

## Non-negotiables (mirrors text-analysis components in this codebase)

- No cloud inference — classification runs entirely on-device via TFLite.
- No image upload.
- No logging of image content/bytes (only warnings about load/inference
  failures are logged, never image data).
- Works offline.
