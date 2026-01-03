## Changes

- Add support for GraalVM native image
- Remove the dependency on co.casterlabs.commons:platform (local copy of necessary code only)
- Remove the dependency on co.casterlabs.commons:io
- Move native library bootstrap into new Bootstrap class
- Replace Lombok `@NonNull` and Jetbrains `@Nullable` with JSpecify
- Replace Lombok `@Getter/@Setter` with code
- Remove Lombok `@SneakyThrows`

