/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * BrassFontsTest.java — the brand's faces in the assets are its files byte for byte (#306)
 *
 */

package com.openipc.decoder;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

public class BrassFontsTest {
    /** SHA256SUMS of local/brand brass/fonts (brass 1.5.1): change both together. */
    private static final Map<String, String> SUMS = new LinkedHashMap<>();

    static {
        SUMS.put("OFL.txt", "7e6b2818edbd8f6a01ae80641cc8f16a51080d08fb4e532be3a0b6f74adb07da");
        SUMS.put(Brass.MONO_BOLD_FILE, "190657fa835af45738bcad97b968dde8148ca0e854c1e8737d518919f2394544");
        SUMS.put(Brass.MONO_FILE, "e139010955613c1a95467f4fa403bd158b5a608855b4eeac7643e0f635256749");
        SUMS.put(Brass.SERIF_FILE, "3d0968d62ec97a044c651bbd86b74974e02452be1522bfd4ddc7e4b50bf30bfc");
        SUMS.put("BrassMono-Bold.woff2", "a533e9dc41fa7cdf54850c4f5deaccad588586bce91a641fbe8448503b8171b6");
        SUMS.put("BrassMono-Regular.woff2", "82556c93d385127871fc82e7338c176b1056c12d9e79f230b49dfe9491544340");
        SUMS.put("BrassSerif-Bold.woff2", "d971f32a0c6e2309fec542eaacf5e394f9995bab62a85f1dae8ab902ad3d1893");
    }

    @Test
    public void theFontsAreTheBrandsFiles() throws IOException, NoSuchAlgorithmException {
        for (Map.Entry<String, String> e : SUMS.entrySet()) {
            byte[] all = Files.readAllBytes(new File("src/main/assets/fonts/" + e.getKey()).toPath());
            StringBuilder hex = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(all)) hex.append(String.format("%02x", b));
            assertEquals(e.getKey(), e.getValue(), hex.toString());
        }
    }

    @Test
    public void theAssetsHoldOnlyTheBrandsFiles() {
        String[] names = new File("src/main/assets/fonts").list();
        assertEquals(SUMS.size(), names == null ? -1 : names.length);
    }
}
