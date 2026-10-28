# Validation status

Checked locally on 2026-10-28:

- Source, manifest, resource references, workflow structure, and configured versions were reviewed.
- The official Gradle wrapper and bundled model passed SHA-256 integrity checks.
- The actual model has a valid TFLite header, readable metadata and 1000 embedded English labels.
- The source ZIP includes the hidden `.github` directory, wrapper JAR and model binary; ZIP CRC checks passed.
- The CameraX and MediaPipe API calls were checked against the exact dependency versions.

Not executed here:

- Android compilation, Android lint and the seven Kotlin unit tests (this environment has no JDK/Android SDK).
- Installation, live-camera testing, accuracy or speed measurements on a phone.

The included GitHub Actions workflow runs the unit tests and Android lint, builds the APK, checks the merged APK has no INTERNET permission, checks the packaged model, and uploads the artifact. A green run confirms these build checks; phone testing is still required.
