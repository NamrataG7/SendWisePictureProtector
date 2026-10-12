# Model Card — NSFW / Nudity Image Classifier

## Status: binary weights NOT bundled

This repo does **not** ship the actual `.tflite` weights for the NSFW image
classifier. Binary model files (trained neural network weights) aren't
something we can author by hand in a code review/PR — they come from an
actual training/conversion pipeline running real image datasets. This file
documents what is expected so the real artifact can be dropped in later
without any code changes.

**Until the binary file is placed at `app/src/main/assets/models/nsfw_classifier.tflite`,
the app is still safe to build and ship.** `NsfwImageClassifier` fails open:
if the asset is missing (which it is, right now), fails to load, or
inference throws for any reason, it logs a warning via
`android.util.Log.w("NsfwImageClassifier", ...)` and returns

```kotlin
ClassificationResult(isSensitive = false, confidence = 0f, label = "unavailable")
```

i.e. it treats every image as non-sensitive and never blocks the user. This
mirrors the fail-open philosophy already used by `ToxicityAnalyzer` /
`EnhancedToxicityAnalyzer` for text.

## What to place here: `nsfw_classifier.tflite`

Recommended source model: **[GantMan/nsfw_model](https://github.com/GantMan/nsfw_model)**
(Keras, MobileNetV2 backbone, MIT-compatible open-source NSFW classifier),
or an equivalent MobileNetV2-based 5-class NSFW classifier trained on the
same taxonomy. Convert the Keras `.h5`/SavedModel weights to TensorFlow
Lite, e.g.:

```python
import tensorflow as tf

model = tf.keras.models.load_model("nsfw_mobilenet2.224x224.h5")
converter = tf.lite.TFLiteConverter.from_keras_model(model)
# Optional: converter.optimizations = [tf.lite.Optimize.DEFAULT]  # for size/speed
tflite_model = converter.convert()

with open("nsfw_classifier.tflite", "wb") as f:
    f.write(tflite_model)
```

Place the resulting file at:

```
app/src/main/assets/models/nsfw_classifier.tflite
```

## Expected tensor shapes

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
