package engineering.swat.watch;

import java.time.Duration;

public class TestHelper {

    public static final Duration SHORT_WAIT;
    public static final Duration NORMAL_WAIT;
    public final static Duration LONG_WAIT;

    static {
        var delayFactorConfig = System.getenv("DELAY_FACTOR");
        int delayFactor = delayFactorConfig == null ? 1 : Integer.parseInt(delayFactorConfig);
        if (System.getProperty("os", "?").toLowerCase().contains("mac")) {
            // OSX is SLOW on it's watches
            delayFactor *= 2;
        }
        SHORT_WAIT = Duration.ofSeconds(1 * delayFactor);
        NORMAL_WAIT = Duration.ofSeconds(4 * delayFactor);
        LONG_WAIT = Duration.ofSeconds(8 * delayFactor);
    }

}
