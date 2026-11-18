/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * WebPortTest.java — the Network field of the web page's port (#284)
 *
 */

package com.openipc.decoder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class WebPortTest {
    @Test
    public void emptyIsTheDefault() {
        assertEquals(Integer.valueOf(8085), WebPort.parse(""));
        assertEquals(Integer.valueOf(8085), WebPort.parse("  "));
        assertEquals(Integer.valueOf(8085), WebPort.parse(null));
        assertNull(WebPort.check(""));
    }

    @Test
    public void portsAnAppCanOpen() {
        assertEquals(Integer.valueOf(8585), WebPort.parse("8585"));
        assertEquals(Integer.valueOf(1024), WebPort.parse("1024"));
        assertEquals(Integer.valueOf(65535), WebPort.parse(" 65535 "));
        assertNull(WebPort.check("8086"));
    }

    @Test
    public void anythingElseIsRefused() {
        for (String s : new String[]{"80", "1023", "65536", "99999", "123456", "-1", "+8085",
                "80a5", "8085.0", "0x1f95"}) {
            assertNull(s, WebPort.parse(s));
            assertNotNull(s, WebPort.check(s));
        }
    }
}
