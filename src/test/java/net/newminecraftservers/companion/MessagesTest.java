package net.newminecraftservers.companion;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class MessagesTest {
    @Test void attributesWebsiteClicksWithoutChangingTheLabel() {
        String url = "https://newminecraftservers.net/server/example?tab=stats#chart";
        String expected = "https://newminecraftservers.net/server/example?tab=stats&utm_source=paper_plugin&utm_medium=referral&utm_campaign=companion_plugin#chart";
        assertEquals(expected, Messages.attributedUrl(url));
        assertEquals(expected, Messages.link("Open listing", url).clickEvent().value());
        assertEquals("https://example.com/", Messages.attributedUrl("https://example.com/"));
        assertTrue(Messages.attributedUrl("https://newminecraftservers.net/dashboard/servers").contains("?utm_source="));
    }
}
