package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.ConnectionState
import com.example.data.model.NearbyUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("CHAT 100m WITHOUT NETWORK", appName)
  }

  @Test
  fun `verify nearby user model`() {
    val user = NearbyUser(
        endpointId = "ep123",
        userId = "rahul#77",
        displayName = "rahul",
        tagDigits = "77",
        estimatedDistanceMeters = 25,
        status = ConnectionState.DISCOVERED
    )
    assertEquals("rahul#77", user.userId)
    assertEquals(25, user.estimatedDistanceMeters)
    assertTrue(user.estimatedDistanceMeters <= 100)
  }
}
