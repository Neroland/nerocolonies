package za.co.neroland.nerocolonies.telemetry;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

/**
 * Log4j2 appender that feeds {@link NeroColoniesTelemetry}. Minecraft routes essentially every
 * failure through log4j — handled errors, event-bus listener exceptions, and the crash report
 * itself — so listening on the root logger catches NeroColonies failures without mixins. Filtering
 * (NeroColonies-only), de-dup, rate-limiting and PII scrubbing all happen in
 * {@link NeroColoniesTelemetry}; this only selects candidate log events.
 */
final class SentryLogAppender extends AbstractAppender {

    SentryLogAppender() {
        super("NeroColoniesSentry", null, null, false, Property.EMPTY_ARRAY);
    }

    @Override
    public void append(LogEvent event) {
        if (!NeroColoniesTelemetry.isActive()) {
            return;
        }
        Level level = event.getLevel();
        if (!level.isMoreSpecificThan(Level.ERROR)) {
            return;
        }
        Throwable thrown = event.getThrown();
        if (thrown != null) {
            if (NeroColoniesTelemetry.touchesNeroColonies(thrown)) {
                NeroColoniesTelemetry.capture(thrown);
            }
        } else if (level == Level.FATAL) {
            String message = event.getMessage() == null ? null : event.getMessage().getFormattedMessage();
            if (message != null && message.contains("za.co.neroland.nerocolonies")) {
                NeroColoniesTelemetry.captureMessage(message);
            }
        }
    }
}
