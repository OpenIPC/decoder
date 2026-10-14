/*
 * Copyright (c) OpenIPC  https://openipc.org  Prosperity Public License 3.0.0
 *
 * KeystoreCipher.java — AES-GCM with a key kept in the Android Keystore
 *
 */

package com.openipc.decoder;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Output layout: 12-byte IV followed by the ciphertext with its 16-byte GCM tag.
 * Only created behind an SDK_INT check in CredentialStore.create(); @RequiresApi would need
 * the AndroidX annotation library, which the app does not use.
 */
@SuppressLint("UseRequiresApi")
@TargetApi(Build.VERSION_CODES.M)
class KeystoreCipher implements CredentialStore.Cipherer {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "camera_urls";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;

    KeystoreCipher() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
            gen.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            gen.generateKey();
        }
        key = (SecretKey) ks.getKey(ALIAS, null);
        if (key == null) throw new IllegalStateException("No key in the Android Keystore");
    }

    /** Deletes the key, so stored URLs can never be read again (Start over, #171). */
    static void deleteKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS);
    }

    @Override
    public byte[] encrypt(byte[] plain) throws Exception {
        Cipher c = Cipher.getInstance(TRANSFORMATION);
        c.init(Cipher.ENCRYPT_MODE, key); // the Keystore picks a random IV
        byte[] iv = c.getIV();
        byte[] ct = c.doFinal(plain);
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return out;
    }

    @Override
    public byte[] decrypt(byte[] sealed) throws Exception {
        if (sealed.length <= IV_LENGTH) throw new IllegalArgumentException("too short");
        Cipher c = Cipher.getInstance(TRANSFORMATION);
        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, sealed, 0, IV_LENGTH));
        return c.doFinal(sealed, IV_LENGTH, sealed.length - IV_LENGTH);
    }
}
