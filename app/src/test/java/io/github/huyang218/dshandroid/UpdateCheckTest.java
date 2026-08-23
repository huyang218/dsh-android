package io.github.huyang218.dshandroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONException;
import org.junit.Test;

/** What may be offered as an update, and — more importantly — what may not. */
public class UpdateCheckTest {

    @Test
    public void newerCoreIsNewer() {
        assertTrue(UpdateCheck.isNewer("0.3.0", "0.2.0"));
        assertTrue(UpdateCheck.isNewer("1.0.0", "0.9.9"));
        assertTrue(UpdateCheck.isNewer("0.2.1", "0.2.0"));
    }

    @Test
    public void sameOrOlderIsNotNewer() {
        assertFalse(UpdateCheck.isNewer("0.2.0", "0.2.0"));
        assertFalse(UpdateCheck.isNewer("0.1.9", "0.2.0"));
    }

    @Test
    public void componentsCompareAsNumbers() {
        // The classic string-compare bug: "10" sorts before "9" as text.
        assertTrue(UpdateCheck.isNewer("0.10.0", "0.9.0"));
        assertFalse(UpdateCheck.isNewer("0.9.0", "0.10.0"));
    }

    @Test
    public void aReleaseOutranksItsPreRelease() {
        // Every version this project has shipped is `-dev`. Getting this rule
        // backwards would make the first real release invisible to all of them.
        assertTrue(UpdateCheck.isNewer("0.2.0", "0.2.0-dev"));
        assertFalse(UpdateCheck.isNewer("0.2.0-dev", "0.2.0"));
    }

    @Test
    public void preReleasesCompareByIdentifier() {
        assertTrue(UpdateCheck.isNewer("0.2.0-rc.2", "0.2.0-rc.1"));
        assertTrue(UpdateCheck.isNewer("0.2.0-rc", "0.2.0-dev"));
        // Numeric identifiers rank below alphanumeric ones (semver 11.4.3).
        assertTrue(UpdateCheck.isNewer("0.2.0-alpha", "0.2.0-1"));
        // More identifiers beats fewer when the shared ones are equal.
        assertTrue(UpdateCheck.isNewer("0.2.0-dev.1", "0.2.0-dev"));
    }

    @Test
    public void aLeadingVIsIgnoredOnEitherSide() {
        assertTrue(UpdateCheck.isNewer("v0.3.0", "0.2.0"));
        assertFalse(UpdateCheck.isNewer("v0.2.0", "v0.2.0"));
    }

    @Test
    public void buildMetadataDoesNotAffectPrecedence() {
        assertFalse(UpdateCheck.isNewer("0.2.0+abc123", "0.2.0"));
        assertFalse(UpdateCheck.isNewer("0.2.0", "0.2.0+abc123"));
    }

    @Test
    public void aVersionThatCannotBeParsedIsNotNewer() {
        // Not comparable resolves to "no update". The other direction would
        // offer a download that Android then refuses, and the app takes the
        // blame for it.
        assertFalse(UpdateCheck.isNewer("nightly", "0.2.0"));
        assertFalse(UpdateCheck.isNewer("0.2.x", "0.2.0"));
        assertFalse(UpdateCheck.isNewer("", "0.2.0"));
        assertFalse(UpdateCheck.isNewer("0.3.0", "not-a-version"));
    }

    /** A release body in the shape GitHub returns. */
    private static String release(String tag, String... assets) {
        StringBuilder json = new StringBuilder("{\"tag_name\":\"").append(tag).append("\",\"assets\":[");
        for (int index = 0; index < assets.length; index++) {
            if (index > 0) json.append(',');
            json.append("{\"name\":\"").append(assets[index])
                .append("\",\"size\":123,\"browser_download_url\":\"https://example/")
                .append(assets[index]).append("\"}");
        }
        return json.append("]}").toString();
    }

    @Test
    public void picksTheApkAndTheSums() throws JSONException {
        UpdateCheck.Available found = UpdateCheck.parseLatest(
                release("v0.3.0", "dsh-android-0.3.0.apk", "SHA256SUMS"), "0.2.0-dev");
        assertNotNull(found);
        assertEquals("0.3.0", found.version);
        assertEquals("dsh-android-0.3.0.apk", found.apkName);
        assertEquals("https://example/dsh-android-0.3.0.apk", found.apkUrl);
        assertEquals("https://example/SHA256SUMS", found.sumsUrl);
        assertEquals(123L, found.bytes);
    }

    @Test
    public void aReleaseWithoutSumsIsStillOffered() throws JSONException {
        UpdateCheck.Available found = UpdateCheck.parseLatest(
                release("v0.3.0", "dsh-android-0.3.0.apk"), "0.2.0-dev");
        assertNotNull(found);
        assertNull(found.sumsUrl);
    }

    @Test
    public void twoApksAreRefusedRatherThanGuessedBetween() throws JSONException {
        // Choosing here would mean choosing which binary to install, and the
        // wrong choice is not something the person can see on the screen.
        assertNull(UpdateCheck.parseLatest(
                release("v0.3.0", "a.apk", "b.apk"), "0.2.0-dev"));
    }

    @Test
    public void aReleaseWithNoApkIsNotAnUpdate() throws JSONException {
        assertNull(UpdateCheck.parseLatest(release("v0.3.0", "notes.txt"), "0.2.0-dev"));
        assertNull(UpdateCheck.parseLatest("{\"tag_name\":\"v0.3.0\"}", "0.2.0-dev"));
    }

    @Test
    public void anOlderReleaseIsNotOfferedEvenWithAnApk() throws JSONException {
        assertNull(UpdateCheck.parseLatest(release("v0.1.0", "old.apk"), "0.2.0-dev"));
    }

    @Test
    public void digestIsFoundByFileName() {
        String digest = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String sums = digest + "  dsh-android-0.3.0.apk\n"
                + "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff  other.tar\n";
        assertEquals(digest, UpdateCheck.digestFor(sums, "dsh-android-0.3.0.apk"));
        assertNull(UpdateCheck.digestFor(sums, "missing.apk"));
    }

    @Test
    public void digestToleratesBinaryMarkerAndPath() {
        String digest = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        assertEquals(digest, UpdateCheck.digestFor(digest + " *out/dsh.apk\n", "dsh.apk"));
    }

    @Test
    public void aTruncatedDigestIsNotAccepted() {
        // A short field means the sums file is not what it claims to be;
        // accepting it would make the check pass for the wrong reason.
        assertNull(UpdateCheck.digestFor("abc123  dsh.apk\n", "dsh.apk"));
    }
}
