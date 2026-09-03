package com.example.bgmistreamer.ui.main

import com.example.bgmistreamer.OverlayItem
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNotNull
import org.junit.Test

class MainScreenViewModelTest {
  @Test
  fun testOverlayItemCreation() {
    val item = OverlayItem(uri = "file://test.png", scalePercent = 50f)
    assertNotNull(item.id)
    assertEquals("file://test.png", item.uri)
    assertEquals(50f, item.scalePercent)
  }

  @Test
  fun testOverlayItemCoordinates() {
    val item = OverlayItem(uri = "file://overlay.png", xPercent = 10f, yPercent = 20f)
    assertEquals(10f, item.xPercent)
    assertEquals(20f, item.yPercent)
  }
}

