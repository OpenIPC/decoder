/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * VersionLineTest.java — the version under Exit in the menu (#308)
 *
 */

package com.openipc.decoder;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class VersionLineTest {
    @Test
    public void aBuildWithoutACommitHasNoComma() {
        assertEquals("v2.30.1, 7677ab0", MenuManager.versionLine("2.30.1", "7677ab0"));
        assertEquals("v2.30.1", MenuManager.versionLine("2.30.1", ""));
        assertEquals("v2.30.1", MenuManager.versionLine("2.30.1", null));
    }
}
