# Native JPEG motion photos

`inspectMotionPhoto(path, cloudMotionHint = false)` is read-only and returns `STATIC`, `NATIVE_MOTION`, `INVALID`, or `UNSUPPORTED`, plus a user-facing diagnostic and the declared MP4 range when applicable. `requireSafeOriginal()` rejects invalid or unsupported structures. Callers must persist the diagnostic when reporting export outcomes.

Read JPEG marker segments and entropy escapes to locate APP1 XMP and the actual primary EOI. Never search arbitrary bytes for the first EOI or ftyp to infer a video offset. Bound XMP size, XML nesting, JPEG segment count, MP4 nesting and box count; disable XML DTD, external entities and schema access.

For MotionPhoto version 1, account for the ordered GContainer items from EOF. Primary padding and explicit positive lengths for additional items are supported; non-primary nonzero padding or missing lengths are unsupported. For MicroVideo version 1, use the explicit MicroVideoOffset. Both declarations must agree when present. The declared range must follow the primary JPEG and contain well-sized ftyp/moov/mdat ISO BMFF boxes. This is a structural check, not a complete track/sample decode or proof of playback on every client.

Validated native originals bypass all EXIF entrypoints without invoking ExifTool. Invalid or unsupported declarations fail before mutation. Ordinary files, including paths under `/tmp`, still receive requested metadata processing. No default transcoding or reinterpretation of presentation timestamps is performed.

`Asset.motionPhotoHint` records observed Xiaomi `description.specialTypeFlagsNew` combinations only (`1006`, or both `1073` and `1065`). A hint never establishes dynamic compatibility. A hint with no actual dynamic structure produces an explicit static-original diagnostic; legacy metadata defaults to false until refreshed. Actual inspection always works without a hint.

Tests use synthetic media. Real originals and hashes remain outside Git. Seven real Xiaomi originals (six modern, one legacy) passed the compiled inspector and remained byte-identical after all three EXIF entrypoints with actual ExifTool configuration.
