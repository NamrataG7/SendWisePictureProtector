"""Export trained sklearn RF + TF-IDF to gzip-JSON for the Kotlin loader.

Emits:
  SafeKeyboardApp/app/src/main/assets/models/sendwise_rf_v1.json.gz       (binary risk)
  SafeKeyboardApp/app/src/main/assets/models/sendwise_category_v1.json.gz (multi-class)

Schema (see RandomForestTextClassifier.kt):
{
  "model_name":  str,
  "version":     str,
  "task":        "binary" | "multiclass",
  "n_features":  int,
  "n_classes":   int,
  "threshold":   float,               // only used for binary
  "classes":     [str, ...],          // class index -> label
  "vocabulary":  { term: index, ... },
  "idf":         [float, ...],        // length = n_features
  "trees": [
    {
      "feature":         [int, ...],  // -2 at leaves
      "threshold":       [float, ...],// -2.0 at leaves
      "children_left":   [int, ...],  // -1 at leaves
      "children_right":  [int, ...],  // -1 at leaves
      "value":           [[float,...],...] // per-node class weights (raw counts)
    }, ...
  ]
}
"""
from __future__ import annotations

import gzip
import json
from pathlib import Path

import joblib
import numpy as np

HERE = Path(__file__).resolve().parent
ART = HERE / "artifacts"
MODELS = HERE.parent / "SafeKeyboardApp" / "app" / "src" / "main" / "assets" / "models"
MODELS.mkdir(parents=True, exist_ok=True)

BINARY_CLASSES = ["non_risk", "risk"]
CATEGORY_CLASSES = ["harassment", "threats", "hate_speech", "sexual_content", "self_harm"]


def tree_to_dict(tree) -> dict:
    t = tree.tree_
    # value shape is (n_nodes, 1, n_outputs=n_classes) — squeeze middle axis
    value = t.value.squeeze(axis=1).tolist()
    return {
        "feature": t.feature.astype(int).tolist(),
        "threshold": t.threshold.astype(float).tolist(),
        "children_left": t.children_left.astype(int).tolist(),
        "children_right": t.children_right.astype(int).tolist(),
        "value": value,
    }


def export_model(rf, vec, classes: list[str], task: str, out_path: Path,
                 model_name: str, version: str = "1.0.0",
                 threshold: float = 0.5) -> None:
    vocab = {term: int(idx) for term, idx in vec.vocabulary_.items()}
    idf = vec.idf_.astype(float).tolist()
    n_features = len(idf)

    payload = {
        "model_name": model_name,
        "version": version,
        "task": task,
        "n_features": n_features,
        "n_classes": len(classes),
        "threshold": threshold,
        "classes": classes,
        "vocabulary": vocab,
        "idf": idf,
        "trees": [tree_to_dict(est) for est in rf.estimators_],
    }
    data = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    with gzip.open(out_path, "wb", compresslevel=6) as f:
        f.write(data)
    size_mb = out_path.stat().st_size / (1024 * 1024)
    print(f"  wrote {out_path.name} ({size_mb:.2f} MB, {len(rf.estimators_)} trees, "
          f"{n_features} features)")


def main() -> int:
    print("Loading binary model ...")
    rf_bin = joblib.load(ART / "rf_model.pkl")
    vec_bin = joblib.load(ART / "tfidf.pkl")

    print("Loading category model ...")
    rf_cat = joblib.load(ART / "rf_category.pkl")
    vec_cat = joblib.load(ART / "tfidf_category.pkl")

    # sklearn's classes_ for RF binary is np.array([0,1]); we map to canonical strings
    assert list(rf_bin.classes_) == [0, 1], f"unexpected classes_: {rf_bin.classes_}"
    assert list(rf_cat.classes_) == list(range(len(CATEGORY_CLASSES))), \
        f"unexpected category classes_: {rf_cat.classes_}"

    print("Exporting binary risk model ...")
    export_model(
        rf_bin, vec_bin, BINARY_CLASSES, "binary",
        MODELS / "sendwise_rf_v1.json.gz",
        model_name="SendWise Risk RandomForest",
    )

    print("Exporting category model ...")
    export_model(
        rf_cat, vec_cat, CATEGORY_CLASSES, "multiclass",
        MODELS / "sendwise_category_v1.json.gz",
        model_name="SendWise Category RandomForest",
    )

    print(f"\nDone. Models in {MODELS}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
