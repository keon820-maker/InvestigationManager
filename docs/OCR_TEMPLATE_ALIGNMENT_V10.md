# OCR v0.10 template alignment

The photographed investigation request form is a fixed layout. The previous v0.9 normalizer could mistake the large central table for the outer paper rectangle. In the provided real sample, the false page quad occupied roughly 26% of the frame and corresponded to the central request/tenant table, so all fixed OCR crops were shifted.

v0.10 uses two alignment modes:

1. A strict outer-page quad when its aspect ratio is close to A4.
2. A fixed-form table anchor when the page edge is cropped or not detectable. The central request/tenant table is mapped to a canonical A4 coordinate rectangle, and the same projective transform is applied to the whole photo.

No source photo bytes are modified; alignment is performed only on an in-memory OCR bitmap.
