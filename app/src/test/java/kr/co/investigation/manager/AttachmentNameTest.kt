package kr.co.investigation.manager

import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentNameTest {
    @Test fun keepsTheStoredExtensionAndSanitizesTheUserLabel() {
        assertEquals("현장_사진.jpg", namedAttachmentFile(" 현장/사진 ", "other_123.jpg"))
        assertEquals("기타 자료", namedAttachmentFile("   ", "attachment"))
    }
}
