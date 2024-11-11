package engineering.swat.watch;

import java.time.Duration;

public class TestHelper {

    public static final Duration SHORT_WAIT;
    public static final Duration NORMAL_WAIT;
    public static final Duration LONG_WAIT;

    static {
        var delayFactorConfig = System.getenv("DELAY_FACTOR");
        int delayFactor = delayFactorConfig == null ? 1 : Integer.parseInt(delayFactorConfig);
        var os = System.getProperty("os", "?").toLowerCase();
        if (os.contains("mac")) {
            // OSX is SLOW on it's watches
            delayFactor *= 2;
        }
        else if (os.contains("win")) {
            // windows watches can be slow to get everything
            // published
            // especially on small core systems
            delayFactor *= 4;
        }
        SHORT_WAIT = Duration.ofSeconds(1 * delayFactor);
        NORMAL_WAIT = Duration.ofSeconds(4 * delayFactor);
        LONG_WAIT = Duration.ofSeconds(8 * delayFactor);
    }

}
