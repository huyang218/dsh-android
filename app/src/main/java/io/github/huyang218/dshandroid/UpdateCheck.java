package io.github.huyang218.dshandroid;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Deciding whether a published release is newer than what is running, and
 * which file to fetch. Pure functions only — no network, no Android — so the
 * one part of updating that fails silently can be tested.
 *
 * <p><b>Why this is separate from the download.</b> An update check has two
 * ways to be wrong and they do not cost the same. Missing an update is a
 * nuisance. Offering an OLDER build as an update is worse: the person taps
 * install, Android refuses the downgrade with a message about the package, and
 * the app looks broken. So every ambiguity here resolves to "no update".
 *
 * <p><b>The version scheme is semver-ish and the pre-release rule matters.</b>
 * Every version this project has shipped carries a {@code -dev} suffix, and
 * semver says {@code 0.2.0-dev} is LOWER than {@code 0.2.0}. Getting that
 * backwards would make the first non-dev release invisible to every dev build
 * in the field.
 *
 * <p><b>The backstop is not this code.</b> Android refuses to install an apk
 * whose versionCode is below the installed one, and refuses one signed with a
 * different key. Those two checks are what actually protect the device; this
 * class only decides whether to bother the person.
 */
final class UpdateCheck {

    /** A release worth offering. */
    static final class Available {
        /** Version as published, without the leading `v`. */
        final String version;
        /** Direct download URL of the apk asset. */
        final String apkUrl;
        /** The asset's file name, used to find its line in SHA256SUMS. */
        final String apkName;
        /** Size in bytes, so the offer can say how much data it will cost. */
        final long bytes;
        /** URL of the SHA256SUMS asset, or null when the release has none. */
        final String sumsUrl;

        Available(String version, String apkUrl, String apkName, long bytes, String sumsUrl) {
            this.version = version;
            this.apkUrl = apkUrl;
            this.apkName = apkName;
            this.bytes = bytes;
            this.sumsUrl = sumsUrl;
        }
    }

    /** The asset carrying one SHA-256 line per file, when a release publishes one. */
    static final String SUMS_NAME = "SHA256SUMS";

    private UpdateCheck() {}

    /**
     * Read a GitHub `releases/latest` body and decide.
     *
     * @param body - the JSON GitHub returned.
     * @param installedVersion - this build's versionName.
     * @return the release to offer, or null when there is nothing newer, when
     *     the release carries no apk, or when it carries more than one.
     * @throws JSONException when the body is not the shape GitHub documents.
     */
    static Available parseLatest(String body, String installedVersion) throws JSONException {
        JSONObject release = new JSONObject(body);
        String tag = release.optString("tag_name", "");
        String version = stripLeadingV(tag);
        if (version.isEmpty() || !isNewer(version, installedVersion)) return null;

        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) return null;

        JSONObject apk = null;
        String sumsUrl = null;
        for (int index = 0; index < assets.length(); index++) {
            JSONObject asset = assets.getJSONObject(index);
            String name = asset.optString("name", "");
            if (name.equals(SUMS_NAME)) {
                sumsUrl = emptyToNull(asset.optString("browser_download_url", ""));
            } else if (name.endsWith(".apk")) {
                // Exactly one, or none. Picking among several would mean
                // guessing which binary to install, and the wrong guess is not
                // recoverable by the person looking at the screen.
                if (apk != null) return null;
                apk = asset;
            }
        }
        if (apk == null) return null;
        String url = apk.optString("browser_download_url", "");
        if (url.isEmpty()) return null;
        return new Available(version, url, apk.optString("name", ""), apk.optLong("size", 0L), sumsUrl);
    }

    /**
     * Strictly-newer comparison over `MAJOR.MINOR.PATCH[-prerelease]`.
     *
     * @param candidate - the published version.
     * @param installed - this build's version.
     * @return true only when `candidate` is definitely newer. A version either
     *     side cannot parse answers false: not comparable is treated as not
     *     newer, because the failure of guessing wrong points the wrong way.
     */
    static boolean isNewer(String candidate, String installed) {
        String[] left = split(candidate);
        String[] right = split(installed);
        if (left == null || right == null) return false;

        int compared = compareCore(left[0], right[0]);
        if (compared != 0) return compared > 0;
        return comparePreRelease(left[1], right[1]) > 0;
    }

    /** `v1.2.3-dev` to `{"1.2.3", "dev"}`, or null when the core is not dotted numbers. */
    private static String[] split(String version) {
        String text = stripLeadingV(version).trim();
        if (text.isEmpty()) return null;
        // Build metadata (`+sha`) never affects precedence, so it is dropped
        // before anything else looks at the string.
        int plus = text.indexOf('+');
        if (plus >= 0) text = text.substring(0, plus);
        int dash = text.indexOf('-');
        String core = dash < 0 ? text : text.substring(0, dash);
        String pre = dash < 0 ? "" : text.substring(dash + 1);
        for (String part : core.split("\\.", -1)) {
            if (part.isEmpty()) return null;
            for (int at = 0; at < part.length(); at++) {
                if (!Character.isDigit(part.charAt(at))) return null;
            }
        }
        return new String[] { core, pre };
    }

    /** Component-wise numeric compare; a missing component counts as 0, so `1.2` equals `1.2.0`. */
    private static int compareCore(String left, String right) {
        String[] a = left.split("\\.", -1);
        String[] b = right.split("\\.", -1);
        int length = Math.max(a.length, b.length);
        for (int index = 0; index < length; index++) {
            long one = index < a.length ? parse(a[index]) : 0L;
            long two = index < b.length ? parse(b[index]) : 0L;
            if (one != two) return one < two ? -1 : 1;
        }
        return 0;
    }

    /**
     * Semver pre-release precedence: absent outranks present, then identifier
     * by identifier, numeric identifiers below alphanumeric ones.
     */
    private static int comparePreRelease(String left, String right) {
        if (left.isEmpty() && right.isEmpty()) return 0;
        // "0.2.0" is a release of "0.2.0-dev", not the other way round.
        if (left.isEmpty()) return 1;
        if (right.isEmpty()) return -1;
        String[] a = left.split("\\.", -1);
        String[] b = right.split("\\.", -1);
        int length = Math.min(a.length, b.length);
        for (int index = 0; index < length; index++) {
            int compared = compareIdentifier(a[index], b[index]);
            if (compared != 0) return compared;
        }
        return Integer.compare(a.length, b.length);
    }

    private static int compareIdentifier(String left, String right) {
        boolean leftNumeric = isNumeric(left);
        boolean rightNumeric = isNumeric(right);
        if (leftNumeric && rightNumeric) return Long.compare(parse(left), parse(right));
        if (leftNumeric) return -1;
        if (rightNumeric) return 1;
        return left.compareTo(right);
    }

    private static boolean isNumeric(String text) {
        if (text.isEmpty()) return false;
        for (int at = 0; at < text.length(); at++) {
            if (!Character.isDigit(text.charAt(at))) return false;
        }
        return true;
    }

    /** A component longer than a long is not a version anyone published; clamp rather than throw. */
    private static long parse(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }

    private static String stripLeadingV(String tag) {
        String text = tag.trim();
        return text.startsWith("v") || text.startsWith("V") ? text.substring(1) : text;
    }

    private static String emptyToNull(String text) {
        return text.isEmpty() ? null : text;
    }

    /**
     * The SHA-256 recorded for one file in a `sha256sum` output.
     *
     * @param sums - the file's contents.
     * @param fileName - the asset name to look for.
     * @return the lowercase digest, or null when the file is not listed.
     */
    static String digestFor(String sums, String fileName) {
        for (String line : sums.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            // `sha256sum` writes "<digest>  <name>"; the binary-mode marker
            // "*" may sit in front of the name.
            int space = trimmed.indexOf(' ');
            if (space < 0) continue;
            String digest = trimmed.substring(0, space);
            String named = trimmed.substring(space).trim();
            if (named.startsWith("*")) named = named.substring(1);
            // Some generators write a path; only the last segment is the name.
            int slash = named.lastIndexOf('/');
            if (slash >= 0) named = named.substring(slash + 1);
            if (named.equals(fileName) && digest.length() == 64) return digest.toLowerCase();
        }
        return null;
    }
}
