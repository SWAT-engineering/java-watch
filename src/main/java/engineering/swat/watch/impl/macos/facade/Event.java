package engineering.swat.watch.impl.macos.facade;

import engineering.swat.watch.impl.macos.LibsFacade.EventFlags;

public class Event {
    public final String path;
    public final EventFlags flags;
    public final long id;

    public Event(String path, int bits, long id) {
        this.path = path;
        this.flags = new EventFlags(bits);
        this.id = id;
    }

    @Override
    public String toString() {
        return path + ", " + flags + ", " + id;
    }
}
