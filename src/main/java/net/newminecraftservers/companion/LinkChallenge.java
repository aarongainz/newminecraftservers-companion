package net.newminecraftservers.companion;

import java.util.concurrent.atomic.AtomicReference;

final class LinkChallenge {
    private final AtomicReference<String> active = new AtomicReference<>();

    void activate(String code) {
        active.set(code);
    }

    /** Claims the MOTD slot; false while another verification is still showing its code. */
    boolean activateIfIdle(String code) {
        return active.compareAndSet(null, code);
    }

    void clear() {
        active.set(null);
    }

    String current() {
        return active.get();
    }

    String decorateText(String motd) {
        String code = active.get();
        return code == null ? motd : motd + "\n" + code;
    }
}

