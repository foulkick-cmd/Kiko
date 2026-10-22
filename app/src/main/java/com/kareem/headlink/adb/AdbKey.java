package com.kareem.headlink.adb;

import android.util.Base64;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/** RSA key pair used to authenticate with adbd, in the same formats as the desktop adb client. */
public final class AdbKey {

    private static final int KEY_BITS = 2048;
    private static final int KEY_BYTES = KEY_BITS / 8;

    // ASN.1 DigestInfo prefix for SHA-1; adbd verifies the token as a SHA-1 digest
    private static final byte[] SHA1_DIGEST_INFO = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };

    private final PrivateKey privateKey;
    private final RSAPublicKey publicKey;

    private AdbKey(PrivateKey privateKey, RSAPublicKey publicKey) {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
    }

    public static AdbKey loadOrCreate(File dir) throws IOException {
        File privFile = new File(dir, "adbkey.pk8");
        File pubFile = new File(dir, "adbkey.x509");
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            if (privFile.exists() && pubFile.exists()) {
                PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(privFile.toPath())));
                RSAPublicKey pub = (RSAPublicKey) kf.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(pubFile.toPath())));
                return new AdbKey(priv, pub);
            }
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(new RSAKeyGenParameterSpec(KEY_BITS, RSAKeyGenParameterSpec.F4));
            KeyPair kp = gen.generateKeyPair();
            try (FileOutputStream o = new FileOutputStream(privFile)) {
                o.write(kp.getPrivate().getEncoded());
            }
            try (FileOutputStream o = new FileOutputStream(pubFile)) {
                o.write(kp.getPublic().getEncoded());
            }
            return new AdbKey(kp.getPrivate(), (RSAPublicKey) kp.getPublic());
        } catch (GeneralSecurityException e) {
            throw new IOException("ADB key error: " + e.getMessage(), e);
        }
    }

    /** PKCS#1 v1.5 signature of the 20-byte token, treated as a SHA-1 digest (what adbd expects). */
    public byte[] sign(byte[] token) throws IOException {
        int tLen = SHA1_DIGEST_INFO.length + token.length;
        if (tLen + 11 > KEY_BYTES) {
            throw new IOException("AUTH token too long: " + token.length);
        }
        byte[] block = new byte[KEY_BYTES];
        block[0] = 0x00;
        block[1] = 0x01;
        int sep = KEY_BYTES - tLen - 1;
        for (int i = 2; i < sep; i++) {
            block[i] = (byte) 0xFF;
        }
        block[sep] = 0x00;
        System.arraycopy(SHA1_DIGEST_INFO, 0, block, sep + 1, SHA1_DIGEST_INFO.length);
        System.arraycopy(token, 0, block, sep + 1 + SHA1_DIGEST_INFO.length, token.length);
        try {
            Cipher c = Cipher.getInstance("RSA/ECB/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, privateKey);
            return c.doFinal(block);
        } catch (GeneralSecurityException e) {
            throw new IOException("ADB sign error: " + e.getMessage(), e);
        }
    }

    /** Public key in the android_pubkey format adbd stores in adb_keys, NUL terminated. */
    public byte[] publicKeyPayload() {
        BigInteger n = publicKey.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = r32.subtract(n.mod(r32).modInverse(r32));
        BigInteger rr = BigInteger.ONE.shiftLeft(KEY_BITS * 2).mod(n);

        ByteBuffer bb = ByteBuffer.allocate(4 + 4 + KEY_BYTES + KEY_BYTES + 4).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(KEY_BYTES / 4);
        bb.putInt(n0inv.intValue());
        bb.put(toLittleEndian(n));
        bb.put(toLittleEndian(rr));
        bb.putInt(publicKey.getPublicExponent().intValue());

        String s = Base64.encodeToString(bb.array(), Base64.NO_WRAP) + " headlink@headunit";
        byte[] str = s.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[str.length + 1];
        System.arraycopy(str, 0, out, 0, str.length);
        return out;
    }

    private static byte[] toLittleEndian(BigInteger v) {
        byte[] be = v.toByteArray();
        byte[] le = new byte[KEY_BYTES];
        for (int i = 0; i < KEY_BYTES; i++) {
            int src = be.length - 1 - i;
            le[i] = src >= 0 ? be[src] : 0;
        }
        return le;
    }
}
