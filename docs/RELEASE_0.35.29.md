# v0.35.29

- Save case edits to the local database before looking up map coordinates. Slow or unavailable geocoding no longer delays persistence or save confirmation.
- Show saving, success and failure feedback. Keep the full-width Save button fixed at the bottom of the editor, including while the phone keyboard is open. Separate attachment actions so they fit narrow screens.
- Preserve coordinates for unchanged addresses. Queue changed addresses independently, and apply results only to the current matching address without overwriting newer text or resurrecting deleted cases.
- Bound Android 13+ asynchronous geocoding to eight seconds across all address variants. Older Android versions retain their blocking platform lookup on an IO thread; edit saving remains independent of that work.

Regression tests use synthetic data: a deferred geocoder verifies that saving completes before lookup and that stale results cannot overwrite a later edit; a deleted record remains deleted; a phone-sized viewport with an open keyboard verifies the fixed Save button and persisted edits.
