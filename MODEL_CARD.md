# Bundled model

This demo includes a pretrained **EfficientNet-Lite0 float32** image classifier. It identifies the dominant content of a camera frame among 1,000 ImageNet classes. The model is already inside the project; the app does not download a model at first launch.

## Provenance

- Provider: Google / MediaPipe.
- Official guide: <https://ai.google.dev/edge/mediapipe/solutions/vision/image_classifier>.
- Downloaded artifact (fixed version 1): <https://storage.googleapis.com/mediapipe-models/image_classifier/efficientnet_lite0/float32/1/efficientnet_lite0.tflite>.
- Local asset: `app/src/main/assets/efficientnet_lite0.tflite`.
- Size: **18,582,189 bytes** (approximately 17.72 MiB).
- SHA-256: `6c7ab0a6e5dcbf38a8c33b960996a55a3b4300b36a018c4545801de3a3c8bde0`.
- Retrieved and inspected: 2026-10-28.

The versioned download URL and hash identify the exact file included here, rather than relying on a changing `latest` URL.

## Input and output

These properties were read from the bundled model's FlatBuffer and embedded metadata:

| Property | Value |
| --- | --- |
| Model metadata name | EfficientNet-lite image classifier |
| Metadata author | MediaPipe |
| Metadata version | 1 |
| Input tensor | `images`, float32, `[1, 224, 224, 3]` |
| Input colour | RGB |
| Normalization mean / standard deviation | `127.0` / `128.0` |
| Output tensor | `Softmax`, float32, `[1, 1000]` |
| Embedded label file | `labels_without_background.txt` |
| Label count | 1,000 English labels |

MediaPipe ImageClassifier reads the metadata and performs resize and normalization. The app passes an RGB-compatible bitmap and rotation information; it must not normalize the bitmap again. The demo uses the CPU delegate for broad device compatibility.

## What the demo can show

Try a clearly visible banana, orange, coffee mug, computer keyboard, or another common ImageNet object in good light. Labels are in English because that is the label file supplied with the model. There are no bounding boxes: this model classifies the overall frame, not each object independently.

The scores rank the model's existing classes. They are not a guarantee that the object is present, and a high score is not a calibrated certainty. An object outside the trained categories can still receive one of the known labels. Accuracy and processing speed depend on the scene, lighting, framing and phone.

## Later replacement with your own model

Keep the same asset filename, or update the filename used by the classifier. A replacement must be compatible with MediaPipe ImageClassifier, not merely have a `.tflite` extension. In particular, supply the correct input normalization metadata and a label file matching the output classes. See the official [custom model requirements](https://ai.google.dev/edge/mediapipe/solutions/vision/image_classifier#custom_models).

Update the expected model SHA-256 in the GitHub Actions workflow when replacing the asset. This prevents accidentally building with a truncated, corrupted or different model.

## License and verification

The bundled model's own metadata states **Apache License, Version 2.0**. The full license text is included in `THIRD_PARTY_NOTICES.md`.

Verified: successful fixed-version download, exact byte count and SHA-256, `TFL3` file identifier, input/output tensor shapes and types, embedded normalization metadata, license string, and all 1,000 embedded labels. These checks validate the asset structure and provenance; they are not a substitute for running the completed APK on a phone.
