/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * CredentialStoreTest.java — camera URLs encrypted at rest (#16)
 *
 */

package com.openipc.decoder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class CredentialStoreTest {
    /** Same layout as KeystoreCipher (IV + ciphertext/tag), with an in-memory key. */
    private static final class TestCipher implements CredentialStore.Cipherer {
        private final SecretKey key;

        TestCipher() throws Exception {
            KeyGenerator g = KeyGenerator.getInstance("AES");
            g.init(256);
            key = g.generateKey();
        }

        @Override public byte[] encrypt(byte[] plain) throws Exception {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain);
            byte[] out = new byte[12 + ct.length];
            System.arraycopy(iv, 0, out, 0, 12);
            System.arraycopy(ct, 0, out, 12, ct.length);
            return out;
        }

        @Override public byte[] decrypt(byte[] sealed) throws Exception {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, sealed, 0, 12));
            return c.doFinal(sealed, 12, sealed.length - 12);
        }
    }

    private static final String URL = "rtsp://admin:s3cr3t@192.168.1.10:554/stream=0";

    @Test
    public void sealedUrlHidesThePasswordAndOpensAgain() throws Exception {
        CredentialStore store = new CredentialStore(new TestCipher());
        String sealed = store.seal(URL);
        assertTrue(sealed.startsWith(CredentialStore.PREFIX));
        assertFalse("no password in the stored value", sealed.contains("s3cr3t"));
        assertFalse(sealed.contains("admin"));
        assertEquals(URL, store.open(sealed));
        // Random IV: the same URL is stored differently each time
        assertNotEquals(sealed, store.seal(URL));
    }

    @Test
    public void plainValuesFromOlderVersionsStillLoadAndAreMigrated() throws Exception {
        CredentialStore store = new CredentialStore(new TestCipher());
        assertEquals(URL, store.open(URL));
        assertTrue(store.needsMigration(URL));
        assertFalse(store.needsMigration(store.seal(URL)));
        assertFalse(store.needsMigration(null));
    }

    @Test
    public void undecryptableValueBecomesAnEmptySlot() throws Exception {
        CredentialStore store = new CredentialStore(new TestCipher());
        String sealed = store.seal(URL);
        // Another key (e.g. data restored on another device)
        assertNull(new CredentialStore(new TestCipher()).open(sealed));
        // Tampered or truncated data
        assertNull(store.open(sealed.substring(0, sealed.length() - 2) + "00"));
        assertNull(store.open(CredentialStore.PREFIX + "zz"));
        assertNull(store.open(CredentialStore.PREFIX + "abc"));
    }

    @Test
    public void withoutKeystoreUrlsStayPlain() {
        CredentialStore store = new CredentialStore(null);
        assertFalse(store.encrypts());
        assertEquals(URL, store.seal(URL));
        assertEquals(URL, store.open(URL));
        assertFalse(store.needsMigration(URL));
        // A sealed value cannot be opened without the Keystore
        assertNull(store.open(CredentialStore.PREFIX + "00ff"));
    }

    @Test
    public void nullStaysNull() throws Exception {
        CredentialStore store = new CredentialStore(new TestCipher());
        assertNull(store.seal(null));
        assertNull(store.open(null));
    }

    @Test
    public void hexRoundTrip() {
        byte[] b = {0, 1, (byte) 0x7F, (byte) 0x80, (byte) 0xFF};
        assertEquals("00017f80ff", CredentialStore.toHex(b));
        assertArrayEquals(b, CredentialStore.fromHex("00017F80ff"));
    }

    @Test
    public void unreadableSlotSurvivesSavingOtherSlots() throws Exception {
        CredentialStore store = new CredentialStore(new TestCipher());
        String placeholder = "rtsp://root:12345@192.168.1.10:554/stream=0";
        String unreadable = CredentialStore.PREFIX + "00112233";
        // Slot not edited: the original stored value is written back unchanged
        assertEquals(unreadable, store.sealForSlot(placeholder, unreadable, placeholder));
        // Slot edited by the user: the new URL is sealed
        String sealed = store.sealForSlot(URL, unreadable, placeholder);
        assertEquals(URL, store.open(sealed));
        // Normal slot
        assertEquals(URL, store.open(store.sealForSlot(URL, null, placeholder)));
    }
}
