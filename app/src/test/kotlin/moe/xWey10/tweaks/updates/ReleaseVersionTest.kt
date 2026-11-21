// SPDX-License-Identifier: GPL-3.0-only
package moe.xWey10.tweaks.updates

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseVersionTest {
    @Test fun newerVersions() {
        assertTrue(ReleaseVersion.isNewer("v0.2.16", "0.2.15"))
        assertTrue(ReleaseVersion.isNewer("0.3.0", "0.2.99"))
        assertTrue(ReleaseVersion.isNewer("v1.0.0", "0.9.999"))
        assertTrue(ReleaseVersion.isNewer("0.2.150", "0.2.15"))
    }

    @Test fun currentAndOlderVersions() {
        assertFalse(ReleaseVersion.isNewer("v0.2.15", "0.2.15"))
        assertFalse(ReleaseVersion.isNewer("0.2.14", "0.2.15"))
        assertFalse(ReleaseVersion.isNewer("v0.2.9", "0.2.15"))
    }

    @Test fun ignorePrereleasesAndUnknownTags() {
        assertFalse(ReleaseVersion.isNewer("v0.2.16-beta1", "0.2.15"))
        assertFalse(ReleaseVersion.isNewer("nightly", "0.2.15"))
        assertFalse(ReleaseVersion.isNewer("v0.2.16", "broken"))
        assertFalse(ReleaseVersion.isValid("v0.2.16-rc"))
        assertTrue(ReleaseVersion.isValid("V0.2.16"))
    }
}
