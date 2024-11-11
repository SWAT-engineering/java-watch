package engineering.swat.watch;

import java.io.Closeable;

/**
 * <p>Marker interface for an active watch, in the future might get properties you can inspect.</p>
 *
 * <p>For now, make sure to close the watch when not interested in new events</p>
 */
public interface ActiveWatch extends Closeable {

}
