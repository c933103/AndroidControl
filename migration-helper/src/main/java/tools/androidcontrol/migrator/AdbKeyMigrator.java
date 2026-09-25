package tools.androidcontrol.migrator;

import android.os.Process;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.GCMParameterSpec;

public final class AdbKeyMigrator {

    private static final String PACKAGE_NAME = "moe.shizuku.privileged.api";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "_adbkey_encryption_key_";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_SIZE = 12;
    private static final int TAG_SIZE = 16;
    private static final byte[] AAD = new byte[16];
    private static final String PREFIX = "ACPAIR1:";

    static {
        byte[] label = "adbkey".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(label, 0, AAD, 0, label.length);
    }

    private AdbKeyMigrator() {
    }

    public static void main(String[] args) {
        try {
            if (args.length != 1) {
                throw new IllegalArgumentException("Usage: export | import");
            }
            if ("export".equals(args[0])) {
                exportKey();
            } else if ("import".equals(args[0])) {
                importKey();
            } else {
                throw new IllegalArgumentException("Usage: export | import");
            }
        } catch (Throwable t) {
            System.err.println("ERROR: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            t.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void exportKey() throws Exception {
        File prefs = findSettingsFile();
        String xml = readUtf8(prefs);
        String stored = extractAdbKey(xml);

        byte[] encrypted = Base64.decode(stored, Base64.NO_WRAP);
        byte[] pkcs8 = decrypt(encrypted, loadExistingEncryptionKey());
        RSAPrivateKey privateKey = validatePrivateKey(pkcs8);

        String fingerprint = hex(MessageDigest.getInstance("SHA-256").digest(privateKey.getModulus().toByteArray()));
        System.out.print(PREFIX);
        System.out.print(Base64.encodeToString(pkcs8, Base64.NO_WRAP));
        System.out.print(":");
        System.out.print(fingerprint);
        System.out.println();
    }

    private static void importKey() throws Exception {
        byte[] input = readAll(System.in);
        String text = new String(input, StandardCharsets.UTF_8).trim();
        if (!text.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Not an AndroidControl pairing export");
        }

        String[] parts = text.substring(PREFIX.length()).split(":", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Malformed pairing export");
        }

        byte[] pkcs8 = Base64.decode(parts[0], Base64.NO_WRAP);
        RSAPrivateKey privateKey = validatePrivateKey(pkcs8);
        String fingerprint = hex(MessageDigest.getInstance("SHA-256").digest(privateKey.getModulus().toByteArray()));
        if (!MessageDigest.isEqual(
                fingerprint.getBytes(StandardCharsets.US_ASCII),
                parts[1].getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("Pairing export integrity check failed");
        }

        Key encryptionKey = getOrCreateEncryptionKey();
        String encrypted = Base64.encodeToString(encrypt(pkcs8, encryptionKey), Base64.NO_WRAP);

        File prefs = preferredSettingsFile();
        File parent = prefs.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Cannot create " + parent);
        }

        String xml;
        if (prefs.exists()) {
            xml = readUtf8(prefs);
            Pattern p = Pattern.compile("<string\\s+name=\"adbkey\">[^<]*</string>");
            Matcher m = p.matcher(xml);
            String replacement = "<string name=\"adbkey\">" + encrypted + "</string>";
            if (m.find()) {
                xml = m.replaceFirst(Matcher.quoteReplacement(replacement));
            } else if (xml.contains("</map>")) {
                xml = xml.replace("</map>", "    " + replacement + "\n</map>");
            } else {
                throw new IllegalStateException("Unrecognized SharedPreferences XML");
            }
        } else {
            xml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                    + "<map>\n"
                    + "    <string name=\"adbkey\">" + encrypted + "</string>\n"
                    + "</map>\n";
        }

        File temp = new File(parent, prefs.getName() + ".migrating");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(xml.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }

        if (prefs.exists() && !prefs.delete()) {
            throw new IllegalStateException("Cannot replace " + prefs);
        }
        if (!temp.renameTo(prefs)) {
            throw new IllegalStateException("Cannot move migrated settings into place");
        }

        System.out.println("OK " + fingerprint);
    }

    private static File preferredSettingsFile() {
        int userId = Process.myUid() / 100000;
        return new File("/data/user_de/" + userId + "/" + PACKAGE_NAME + "/shared_prefs/settings.xml");
    }

    private static File findSettingsFile() {
        int userId = Process.myUid() / 100000;
        File[] candidates = new File[] {
                new File("/data/user_de/" + userId + "/" + PACKAGE_NAME + "/shared_prefs/settings.xml"),
                new File("/data/user/" + userId + "/" + PACKAGE_NAME + "/shared_prefs/settings.xml"),
                new File("/data/data/" + PACKAGE_NAME + "/shared_prefs/settings.xml")
        };

        for (File candidate : candidates) {
            if (candidate.isFile()) {
                return candidate;
            }
        }
        throw new IllegalStateException("settings.xml not found");
    }

    private static String extractAdbKey(String xml) {
        Matcher m = Pattern.compile("<string\\s+name=\"adbkey\">([^<]+)</string>").matcher(xml);
        if (!m.find()) {
            throw new IllegalStateException("adbkey not found in settings.xml");
        }
        return m.group(1).trim();
    }

    private static Key loadExistingEncryptionKey() throws Exception {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        Key key = store.getKey(KEY_ALIAS, null);
        if (key == null) {
            throw new IllegalStateException("Existing Android Keystore encryption key not found");
        }
        return key;
    }

    private static Key getOrCreateEncryptionKey() throws Exception {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        Key key = store.getKey(KEY_ALIAS, null);
        if (key != null) {
            return key;
        }

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private static byte[] decrypt(byte[] ciphertext, Key key) throws Exception {
        if (ciphertext.length < IV_SIZE + TAG_SIZE) {
            throw new IllegalArgumentException("Encrypted adbkey is too short");
        }

        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                new GCMParameterSpec(TAG_SIZE * 8, ciphertext, 0, IV_SIZE));
        cipher.updateAAD(AAD);
        return cipher.doFinal(ciphertext, IV_SIZE, ciphertext.length - IV_SIZE);
    }

    private static byte[] encrypt(byte[] plaintext, Key key) throws Exception {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        cipher.updateAAD(AAD);

        byte[] result = new byte[IV_SIZE + plaintext.length + TAG_SIZE];
        cipher.doFinal(plaintext, 0, plaintext.length, result, IV_SIZE);
        System.arraycopy(cipher.getIV(), 0, result, 0, IV_SIZE);
        return result;
    }

    private static RSAPrivateKey validatePrivateKey(byte[] pkcs8) throws Exception {
        PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        if (!(key instanceof RSAPrivateKey)) {
            throw new IllegalArgumentException("Stored ADB key is not RSA");
        }
        return (RSAPrivateKey) key;
    }

    private static String readUtf8(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file)) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        }
    }

    private static byte[] readAll(java.io.InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static String hex(byte[] value) {
        StringBuilder sb = new StringBuilder(value.length * 2);
        for (byte b : value) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
