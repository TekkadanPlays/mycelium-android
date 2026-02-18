package social.mycelium.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Baseline Profile Generator for Mycelium
 * 
 * Generates optimized ahead-of-time (AOT) compilation profiles based on real user journeys.
 * This replaces the manually-written baseline-prof.txt with data from actual profiling.
 * 
 * Based on official Android guidance:
 * https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile
 * 
 * To generate the profile:
 * ```
 * ./gradlew :benchmark:generateBaselineProfile
 * ```
 * 
 * The generated profile will be in: app/src/main/generated/baselineProfiles/
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = "social.mycelium.android.debug", // Match app applicationId (debug variant)
        maxIterations = 15, // Run multiple times to get stable results
        stableIterations = 3, // Must be stable for 3 iterations
        includeInStartupProfile = true
    ) {
        // ==================================================================
        // CRITICAL USER JOURNEY 1: App Startup and Initial View
        // ==================================================================
        pressHome()
        startActivityAndWait()
        
        // Wait for main screen to load
        device.wait(Until.hasObject(By.pkg(packageName)), 5000)
        device.waitForIdle()
        
        // ==================================================================
        // CRITICAL USER JOURNEY 2: Scroll Through Feed
        // ==================================================================
        // Simulate user scrolling through notes (most common action)
        val feedList = device.wait(
            Until.findObject(By.scrollable(true)),
            3000
        )
        
        if (feedList != null) {
            // Scroll down
            repeat(5) {
                feedList.scroll(Direction.DOWN, 0.8f)
                device.waitForIdle()
            }
            
            // Scroll back up
            repeat(3) {
                feedList.scroll(Direction.UP, 0.8f)
                device.waitForIdle()
            }
        }
        
        // ==================================================================
        // CRITICAL USER JOURNEY 3: Interact with Notes
        // ==================================================================
        // Find and tap the first note's like button
        val likeButton = device.wait(
            Until.findObject(By.desc("Upvote")),
            2000
        )
        likeButton?.click()
        device.waitForIdle()
        
        // ==================================================================
        // CRITICAL USER JOURNEY 4: Open Menu/Sidebar
        // ==================================================================
        val menuButton = device.wait(
            Until.findObject(By.desc("Menu")),
            2000
        )
        menuButton?.click()
        device.waitForIdle()
        
        // Close menu
        device.pressBack()
        device.waitForIdle()
        
        // ==================================================================
        // CRITICAL USER JOURNEY 5: Search Functionality
        // ==================================================================
        val searchButton = device.wait(
            Until.findObject(By.desc("Search")),
            2000
        )
        searchButton?.click()
        device.waitForIdle()
        
        // Type search query
        val searchField = device.wait(
            Until.findObject(By.focused(true)),
            2000
        )
        searchField?.text = "test"
        device.waitForIdle()
        
        // Exit search
        device.pressBack()
        device.waitForIdle()
        
        // ==================================================================
        // CRITICAL USER JOURNEY 6: Navigate to Profile
        // ==================================================================
        val profileButton = device.wait(
            Until.findObject(By.desc("Profile")),
            2000
        )
        profileButton?.click()
        device.waitForIdle()
        
        // Scroll profile
        val profileList = device.wait(
            Until.findObject(By.scrollable(true)),
            2000
        )
        profileList?.scroll(Direction.DOWN, 0.5f)
        device.waitForIdle()
        
        // Return to home
        device.pressBack()
        device.waitForIdle()
        
        // ==================================================================
        // CRITICAL USER JOURNEY 7: Settings Navigation
        // ==================================================================
        // Open menu again
        menuButton?.click()
        device.waitForIdle()
        
        val settingsButton = device.wait(
            Until.findObject(By.text("Settings")),
            2000
        )
        settingsButton?.click()
        device.waitForIdle()
        
        // Navigate back to home
        device.pressBack()
        device.waitForIdle()
        device.pressBack() // Close menu
        device.waitForIdle()
        
        // ==================================================================
        // Final: Return to home screen and let app settle
        // ==================================================================
        pressHome()
        device.waitForIdle()
    }
}

/**
 * Expected Performance Improvements:
 * 
 * After generating and applying the real baseline profile:
 * - Cold start: 20-30% faster
 * - Warm start: 15-20% faster  
 * - First frame: Rendered sooner
 * - Scroll performance: Smoother on first interaction
 * 
 * The profile tells the system which code paths to pre-compile,
 * so they're ready to execute without JIT compilation delays.
 */

