# v0.35.28

- Delegate document capture to the camera app without declaring our own CAMERA permission. Grant the single output URI read/write access, retain its path through recreation, and handle cancellation/unavailable apps without terminating registration.
- Prefer Samsung Gallery for local image selection; use a local-only document picker on other devices. Apply the same intake handling to confirmation attachments.
- Restore the active destination and return destination after rotation. Preserve sheet filters/sorting and registration/detail drafts. Running OCR belongs to the ViewModel and survives activity recreation.
- Toggle ascending/descending sorting from each sheet column header; use Korean text ordering and natural numeric ordering, with empty cells last. Remove the year column/filter and visit-order column.
- Search across sheet fields and combine per-column value filters. Print a snapshot of the visible filtered rows in the current order; wide tables repeat identifying columns, and long cells continue across pages.
- Enter selection mode with the sheet Delete button, select visible cases, then confirm moving only that selection to the existing trash. Preserve attachments and support restoration. Selection survives rotation and excludes rows hidden by changed filters.
- Add an Address Change button to the case editor for property/owner/custom map locations. Ordinary Save persists edits without reopening the location chooser. Keep custom addresses separately, include them in sync/export and the generated request form/PDF, and offer the custom location in navigation.
- Migrate database version 6 to 7 by adding one address column; existing records and attachments are retained.

Regression coverage uses synthetic data only: camera handoff/cancellation, local picker intents, destination restoration during tablet rotation, sorting/filter restoration, filtered print snapshots and pagination, selected-case deletion, custom-address validation/form rendering, PDF wrapping/continuation, and migration of an existing row.

Android references: [delegated image capture](https://developer.android.com/reference/android/provider/MediaStore#ACTION_IMAGE_CAPTURE), [local-only content selection](https://developer.android.com/reference/android/content/Intent#EXTRA_LOCAL_ONLY), [saving Compose state](https://developer.android.com/develop/ui/compose/state-saving).
