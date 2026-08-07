package za.co.neroland.nerocolonies.telemetry;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;

import io.sentry.Breadcrumb;
import io.sentry.ITransaction;
import io.sentry.Sentry;
import io.sentry.SentryEvent;
import io.sentry.SentryLevel;
import io.sentry.SpanStatus;
import io.sentry.protocol.Message;
import io.sentry.protocol.SentryException;
import io.sentry.protocol.SentryStackFrame;
import io.sentry.protocol.SentryStackTrace;

import za.co.neroland.nerocolonies.NeroColoniesCommon;
import za.co.neroland.nerocolonies.config.NeroColoniesConfig;
import za.co.neroland.nerocolonies.platform.Services;

/**
 * Crash/error reporting for NeroColonies via Sentry (EU ingest), built to satisfy both the
 * CurseForge moderation rule (external analytics must be disclosed and opt-out-able) and
 * POPIA/GDPR data-minimisation:
 *
 * <ul>
 *   <li><b>Opt-out:</b> gated on {@code telemetryEnabled} in {@link NeroColoniesConfig} (default ON,
 *       disclosed). Set it false to stop reporting (takes effect on restart).</li>
 *   <li><b>NeroColonies errors only:</b> {@code beforeSend} drops any event whose stack trace does
 *       not touch {@code za.co.neroland.nerocolonies}.</li>
 *   <li><b>No personal data:</b> {@code sendDefaultPii=false} (no IP), no server/host name, no user
 *       identity, no player names/UUIDs, no colony ownership or access-log data, and OS-account
 *       names are scrubbed from file paths. Remaining payload: stack trace,
 *       mod/MC/loader/OS/Java versions.</li>
 *   <li><b>Bounded volume:</b> per-session de-duplication plus a hard cap of
 *       {@value #MAX_EVENTS_PER_SESSION} events per game session.</li>
 * </ul>
 *
 * <p>{@link #init()} is called once per loader at bootstrap (from {@code NeroColoniesCommon.init()},
 * right after the config schema is registered) and reads loader facts through
 * {@link Services#PLATFORM}.
 */
public final class NeroColoniesTelemetry {

    /**
     * ============================ THE UNCONFIGURED-BUILD GUARD ============================
     * While {@link #DSN} equals this literal, every entry point below is a hard no-op:
     * {@link #init()} returns before touching the Sentry SDK, so <b>nothing is ever sent
     * anywhere</b> and no network connection is opened, regardless of the {@code telemetryEnabled}
     * config value.
     *
     * <p>A real NeroColonies Sentry project is now configured, so this branch is <b>dormant</b> in
     * shipped builds: telemetry is live and opt-out. <b>Do not remove the guard</b> — it is what
     * keeps a fork, a stripped build or a half-configured branch silent instead of crashing on SDK
     * init or reporting into somebody else's project.
     * ===================================================================================
     */
    private static final String PLACEHOLDER_DSN = "https://REPLACE-ME@sentry.invalid/0";

    /**
     * Sentry DSN — a public client key (write-only ingest), safe to ship in the jar. It grants
     * permission to SEND events and nothing else: it cannot read issues, and it identifies the
     * NeroColonies project, never a player. This is the real production DSN, so reporting is live
     * unless the player has opted out via {@code telemetryEnabled=false}.
     */
    private static final String DSN = "https://a78299dcdd327f6c5fc574aaab31930f@o4511183823241216.ingest.de.sentry.io/4511860057964624";

    /** Stack traces must contain this package for an event to be sent. */
    private static final String PACKAGE_MARKER = "za.co.neroland.nerocolonies";

    /** Hard cap on events per game session (data minimisation + noise control). */
    private static final int MAX_EVENTS_PER_SESSION = 10;

    /** Cap on how many mod ids are attached as crash context (payload bound). */
    private static final int MAX_MODS_REPORTED = 300;

    /** Masks OS-account names in Windows/macOS/Linux home-directory paths. */
    private static final Pattern USER_PATH =
            Pattern.compile("(?i)(?:[A-Z]:)?[/\\\\](?:Users|home)[/\\\\][^/\\\\\\s:;,'\"]+");

    private static volatile boolean active;
    private static final AtomicInteger eventsSent = new AtomicInteger();
    private static final Set<String> seenFingerprints = ConcurrentHashMap.newKeySet();
    private static SentryLogAppender appender;

    private NeroColoniesTelemetry() {
    }

    /**
     * True only if the shipped DSN has been stripped back to the placeholder (fork / unconfigured
     * build), in which case telemetry stays wired but inert. False in released builds.
     */
    private static boolean dsnIsPlaceholder() {
        return PLACEHOLDER_DSN.equals(DSN);
    }

    /**
     * Called once per loader at bootstrap. Starts reporting iff a real DSN is configured AND the
     * player has not opted out ({@code telemetryEnabled=true}, the default). Dev (IDE) runs ALSO
     * report — so the developer can test error reporting end to end — but they are tagged
     * {@code environment=development} / {@code runtime=development} so they are trivially filtered
     * out of production metrics.
     */
    public static void init() {
        if (dsnIsPlaceholder()) {
            // No Sentry project configured: stay completely inert. Logged once at DEBUG so a
            // developer can see why no events arrive, without nagging players.
            NeroColoniesCommon.LOGGER.debug(
                    "[NeroColonies] Telemetry inert - no Sentry DSN configured in this build.");
            return;
        }
        if (!NeroColoniesConfig.isTelemetryEnabled()) {
            return;
        }
        start();
    }

    /**
     * Fires a single synthetic Sentry event to confirm end-to-end reporting on a real (production)
     * jar. The exception originates in NeroColonies code so it passes the package-only
     * {@code beforeSend} filter; the per-session de-dup means repeat calls in one session collapse
     * to one event (restart to test again).
     *
     * @return {@code true} if telemetry is active and the event was dispatched
     */
    public static boolean sendTestEvent(String origin) {
        if (!active) {
            return false;
        }
        capture(new IllegalStateException(
                "NeroColonies Sentry test (" + origin + ") — synthetic event, safe to ignore"));
        return true;
    }

    private static synchronized void start() {
        if (active) {
            return;
        }
        String modVersion = Services.PLATFORM.getModVersion();
        boolean dev = Services.PLATFORM.isDevelopmentEnvironment();
        Sentry.init(options -> {
            options.setDsn(DSN);
            options.setRelease("nerocolonies@" + modVersion);
            // Dev/IDE runs report under a dedicated environment so they never mix with real releases.
            options.setEnvironment(dev ? "development" : environmentOf(modVersion));
            // POPIA/GDPR: never store the sender's IP address or identity.
            options.setSendDefaultPii(false);
            // The machine's hostname is identifying; never attach it.
            options.setAttachServerName(false);
            options.setEnableUncaughtExceptionHandler(true);
            // Release health: session lifecycle managed manually (started below, ended on JVM
            // shutdown). The session id is random per launch and is NOT linked across sessions.
            options.setEnableAutoSessionTracking(false);
            // Performance: sample a small fraction of traced operations (colony tick, housing scan).
            // Timing data only — no personal data in a transaction.
            options.setTracesSampleRate(0.05D);
            options.setBeforeSend((event, hint) -> filterAndScrub(event));
        });
        Sentry.configureScope(scope -> {
            scope.setTag("loader", Services.PLATFORM.getPlatformName().toLowerCase(Locale.ROOT));
            scope.setTag("dist", Services.PLATFORM.isClient() ? "client" : "dedicated_server");
            scope.setTag("runtime", dev ? "development" : "production");
            scope.setTag("mc_version", minecraftVersion());
            // Config values — surfaces crashes that only reproduce at non-default settings. These are
            // server/pack settings, never player data.
            scope.setTag("cfg_max_colonies_total",
                    Integer.toString(NeroColoniesConfig.MAX_COLONIES_TOTAL.get()));
            scope.setTag("cfg_claim_radius",
                    Integer.toString(NeroColoniesConfig.CLAIM_RADIUS.get()));
            scope.setTag("cfg_colonists_per_colony",
                    Integer.toString(NeroColoniesConfig.COLONISTS_PER_COLONY.get()));
            scope.setTag("cfg_colony_tick_interval",
                    Integer.toString(NeroColoniesConfig.COLONY_TICK_INTERVAL_TICKS.get()));
            // Loaded-mod list (public manifest ids + versions only) for mod-conflict triage.
            try {
                List<String> mods = Services.PLATFORM.getLoadedModIds();
                if (mods != null && !mods.isEmpty()) {
                    if (mods.size() > MAX_MODS_REPORTED) {
                        mods = mods.subList(0, MAX_MODS_REPORTED);
                    }
                    scope.setTag("mod_count", Integer.toString(mods.size()));
                    java.util.Map<String, Object> modContext = new java.util.HashMap<>();
                    modContext.put("count", mods.size());
                    modContext.put("ids", mods);
                    scope.setContexts("loaded_mods", modContext);
                }
            } catch (RuntimeException | LinkageError e) {
                // Mod list not available this early — skip it; the rest of the report is unaffected.
            }
        });
        // Manual release-health session for this play session; closed on a clean JVM shutdown.
        Sentry.startSession();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Sentry.endSession();
                Sentry.flush(2000L);
            } catch (RuntimeException ignored) {
                // best-effort flush on shutdown
            }
        }, "nerocolonies-sentry-shutdown"));
        if (appender == null) {
            appender = new SentryLogAppender();
            appender.start();
            ((org.apache.logging.log4j.core.Logger) LogManager.getRootLogger()).addAppender(appender);
        }
        active = true;
        NeroColoniesCommon.LOGGER.info(
                "[NeroColonies] Telemetry enabled (anonymous error reports, EU servers; opt out via "
                        + "telemetryEnabled=false in config/nerocolonies.properties).");
    }

    /**
     * Drops a lightweight, non-identifying breadcrumb onto the current scope — a trail of what the
     * mod was doing (colony tick ran, housing rescanned) that rides along with the next error report.
     * No-op when telemetry is off. The message is scrubbed of OS-account paths exactly like every
     * other payload, and must never be given player names, UUIDs or colony ownership values.
     */
    public static void breadcrumb(String category, String message) {
        if (!active) {
            return;
        }
        Breadcrumb crumb = new Breadcrumb();
        crumb.setType("default");
        crumb.setCategory(category);
        crumb.setLevel(SentryLevel.INFO);
        crumb.setMessage(scrub(message));
        Sentry.addBreadcrumb(crumb);
    }

    /**
     * Times a unit of work as a sampled Sentry transaction (performance tracing). Returns the body's
     * value. When telemetry is off the body simply runs untraced, so call sites stay branch-free.
     * Only timing + operation name are recorded — never personal data.
     */
    public static <T> T trace(String operation, String name, java.util.function.Supplier<T> body) {
        if (!active) {
            return body.get();
        }
        ITransaction tx = Sentry.startTransaction(name, operation);
        try {
            return body.get();
        } catch (RuntimeException | Error e) {
            tx.setThrowable(e);
            tx.setStatus(SpanStatus.INTERNAL_ERROR);
            throw e;
        } finally {
            tx.finish();
        }
    }

    /** {@link #trace(String, String, java.util.function.Supplier)} for a body with no return value. */
    public static void trace(String operation, String name, Runnable body) {
        trace(operation, name, () -> {
            body.run();
            return null;
        });
    }

    /**
     * The running Minecraft version, read from the loader's mod list (where "minecraft" is always
     * present) so we stay off version-specific vanilla version APIs. "unknown" if unavailable.
     */
    private static String minecraftVersion() {
        try {
            for (String mod : Services.PLATFORM.getLoadedModIds()) {
                if (mod.startsWith("minecraft ")) {
                    return mod.substring("minecraft ".length());
                }
            }
        } catch (RuntimeException | LinkageError e) {
            // fall through to unknown
        }
        return "unknown";
    }

    /** Maps the mod version's release channel to a Sentry environment. */
    private static String environmentOf(String version) {
        String v = version.toLowerCase(Locale.ROOT);
        if (v.contains("-alpha")) {
            return "alpha";
        }
        if (v.contains("-beta")) {
            return "beta";
        }
        return "production";
    }

    static boolean isActive() {
        return active;
    }

    /** True if any frame of the throwable (or its causes/suppressed) is NeroColonies code. */
    static boolean touchesNeroColonies(Throwable t) {
        int depth = 0;
        while (t != null && depth++ < 16) {
            for (StackTraceElement el : t.getStackTrace()) {
                if (el.getClassName().startsWith(PACKAGE_MARKER)) {
                    return true;
                }
            }
            for (Throwable s : t.getSuppressed()) {
                if (touchesNeroColonies(s)) {
                    return true;
                }
            }
            t = t.getCause();
        }
        return false;
    }

    /** Capture an exception already known to touch NeroColonies code. */
    static void capture(Throwable t) {
        if (!active || t == null) {
            return;
        }
        Sentry.captureException(t);
    }

    /** Capture a handled exception if it is still clearly from NeroColonies code. */
    public static void captureHandledException(Throwable t) {
        if (t != null && touchesNeroColonies(t)) {
            capture(t);
        }
    }

    /** Capture a handled exception with a non-identifying source label for triage. */
    public static void captureHandledException(Throwable t, String source, String operation) {
        if (!active || t == null || !touchesNeroColonies(t)) {
            return;
        }
        Sentry.withScope(scope -> {
            scope.setTag("handled", "true");
            scope.setTag("source", source);
            scope.setExtra("operation", operation);
            Sentry.captureException(t);
        });
    }

    /** Capture a (scrubbed, truncated) FATAL log line that names NeroColonies without a throwable. */
    static void captureMessage(String message) {
        if (!active) {
            return;
        }
        String scrubbed = scrub(message);
        if (scrubbed.length() > 4000) {
            scrubbed = scrubbed.substring(0, 4000) + "…[truncated]";
        }
        SentryEvent event = new SentryEvent();
        event.setLevel(SentryLevel.FATAL);
        Message msg = new Message();
        msg.setFormatted(scrubbed);
        event.setMessage(msg);
        Sentry.captureEvent(event);
    }

    /**
     * The single privacy/noise gate every outgoing event passes through: keep only
     * NeroColonies-related events, de-duplicate, rate-limit, and scrub PII. Returning {@code null}
     * drops the event.
     */
    private static SentryEvent filterAndScrub(SentryEvent event) {
        if (!isNeroColoniesRelated(event)) {
            return null;
        }
        String fingerprint = fingerprintOf(event);
        if (!seenFingerprints.add(fingerprint)) {
            return null; // already reported this session
        }
        if (eventsSent.incrementAndGet() > MAX_EVENTS_PER_SESSION) {
            return null;
        }
        // POPIA/GDPR scrubbing: no user identity, no hostname, no OS-account names in paths.
        event.setUser(null);
        event.setServerName(null);
        List<SentryException> scrubExceptions = event.getExceptions();
        if (scrubExceptions != null) {
            for (SentryException ex : scrubExceptions) {
                String value = ex.getValue();
                if (value != null) {
                    ex.setValue(scrub(value));
                }
                SentryStackTrace st = ex.getStacktrace();
                List<SentryStackFrame> frames = st == null ? null : st.getFrames();
                if (frames != null) {
                    for (SentryStackFrame frame : frames) {
                        frame.setAbsPath(null);
                    }
                }
            }
        }
        Message message = event.getMessage();
        if (message != null && message.getFormatted() != null) {
            message.setFormatted(scrub(message.getFormatted()));
        }
        return event;
    }

    private static boolean isNeroColoniesRelated(SentryEvent event) {
        Throwable t = event.getThrowable();
        if (t != null && touchesNeroColonies(t)) {
            return true;
        }
        List<SentryException> exceptions = event.getExceptions();
        if (exceptions != null) {
            for (SentryException ex : exceptions) {
                SentryStackTrace st = ex.getStacktrace();
                List<SentryStackFrame> frames = st == null ? null : st.getFrames();
                if (frames == null) {
                    continue;
                }
                for (SentryStackFrame frame : frames) {
                    String module = frame.getModule();
                    if (module != null && module.startsWith(PACKAGE_MARKER)) {
                        return true;
                    }
                }
            }
        }
        Message message = event.getMessage();
        String formatted = message == null ? null : message.getFormatted();
        return formatted != null && formatted.contains(PACKAGE_MARKER);
    }

    private static String fingerprintOf(SentryEvent event) {
        StringBuilder sb = new StringBuilder();
        List<SentryException> exceptions = event.getExceptions();
        Message message = event.getMessage();
        if (exceptions != null) {
            for (SentryException ex : exceptions) {
                sb.append(ex.getType()).append('|');
                SentryStackTrace st = ex.getStacktrace();
                List<SentryStackFrame> frames = st == null ? null : st.getFrames();
                if (frames != null) {
                    for (SentryStackFrame frame : frames) {
                        String module = frame.getModule();
                        if (module != null && module.startsWith(PACKAGE_MARKER)) {
                            sb.append(module).append(':').append(frame.getLineno()).append('|');
                        }
                    }
                }
            }
        } else if (message != null) {
            String formatted = message.getFormatted();
            if (formatted != null) {
                sb.append(formatted, 0, Math.min(200, formatted.length()));
            }
        }
        return sb.toString();
    }

    /** Replaces home-directory paths (which contain the OS account name) with a neutral marker. */
    static String scrub(String text) {
        return USER_PATH.matcher(text).replaceAll("/~");
    }
}
