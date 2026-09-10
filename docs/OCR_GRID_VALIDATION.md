# OCR: verified cell boundaries

The request form previously passed through a fixed-coordinate parser and many repairs that
could overwrite the same fields. A nonempty field count is not an accuracy measurement:
blank tenant/phone cells are legitimate. Finding a plausible number elsewhere on the page
is not evidence that it belongs to a particular role.

The grid route now:

1. Prefers the central printed table over paper edges, removes residual skew from long
   lines, and keeps the actual four corners for perspective correction.
2. Detects cell contours and validates the complete row/column structure plus three OCR
   labels before assigning any field. Unsupported layouts retain the legacy route.
3. Reads only each value cell, including all ten tenant name/phone pairs. A blank cell
   remains blank. Shared phone numbers remain valid when printed in multiple roles.
4. Compares original and gentle contrast reads within that same cell. Cell enhancement
   does not erase character strokes. Conflicting identities/numbers are left empty;
   free-text candidates remain visible with a review warning. Whitespace-only differences
   and spaces within birth-date digits are normalized. Empty tenant cells with no
   character-sized ink skip OCR.
5. Returns the verified grid result directly; subsequent legacy repairs cannot replace
   its values, invent a tenant from a debtor phone, or substitute a guessed building name.
6. Uses the bundled Korean ML Kit recognizer. The primary route does not send images to
   a cloud OCR provider. It omits the fixed investigator row from the header crop.
7. Batches isolated crops into panels grouped by width, without rescaling the crops.
   Identities and tenant names retain individual reads after a photo regression exposed
   recognition differences for short Korean names in mixed panels.
   Whitespace separates cells; a recognized line must fit one cell to be assigned.
   Missing reads with visible ink are retried only in that same source cell. A device
   regression test verifies distinct contacts after batch reordering and wrapped lines.

## Verification

- `gradle testDebugUnitTest assembleDebug` runs parser regression tests and builds the app.
- `GridFormOcrInstrumentedTest` draws non-personal scan/photo forms on device. Assertions
  cover birth dates, the full secured-loan label, repeated contacts, empty tenants and a
  wrapped tenant phone. Synthetic geometry is deliberately distinct from private images.
- Optional `PrivateOcrFixtureTest` accepts local images only when explicitly enabled, and
  refuses to run if the target app has INTERNET permission. It writes results only into
  the target app's private directory, with no raw values in assertions or logs.

Build the isolated test app and instrumentation:

```sh
gradle -PincludeEmulatorAbi=true -PprivateOcrValidation=true \
  assemblePrivateValidation assemblePrivateValidationAndroidTest
```

The validation app has a separate package ending in `.ocrvalidation`, INTERNET permission
removed by the manifest merger, and Firebase/maps disabled. The ordinary app's existing
optional sync behavior is unchanged.

For local private testing, transfer images using `adb exec-in run-as` to the validation
app's `files/private-ocr-input/`, then run only `PrivateOcrFixtureTest` with instrumentation
argument `privateFixtures=true`. Results are in `files/private-ocr-output/`. Do not commit
or upload those directories, real images, expected values or generated OCR reports to CI.
Each local result includes the exact source filename and SHA-256 to prevent confusing
documents after a re-upload. Optional `sampleOffset`/`sampleLimit` select a bounded batch;
`gridOnly=true` tests the primary grid route, and `individualCells=true` disables batching
for comparisons within that route.

A passed build is not a claim of 100% OCR accuracy. Compare critical fields with the source
before saving, especially handwriting, blurred text and conflicting reads. Upscaling
cannot restore missing image detail; ML Kit's [input image guidelines](https://developers.google.com/ml-kit/vision/text-recognition/v2/android#input-image-guidelines)
recommend sufficient native character pixels.
