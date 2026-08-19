import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;

import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Minimal apksig driver: signs an APK with the v2 scheme and verifies the
 * result the way the Android platform would. v2-only is sufficient for
 * minSdkVersion >= 24 (Android 7.0 introduced v2), and apksig 2.3.0's v1
 * signer depends on JDK internals that no longer exist on modern JDKs.
 *
 * Usage: Signer <keystore> <alias> <password> <in.apk> <out.apk>
 */
public class Signer {
    public static void main(String[] args) throws Exception {
        String ksPath = args[0], alias = args[1], pass = args[2];
        File in = new File(args[3]), out = new File(args[4]);

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(ksPath)) {
            ks.load(fis, pass.toCharArray());
        }
        PrivateKey key = (PrivateKey) ks.getKey(alias, pass.toCharArray());
        List<X509Certificate> certs = new ArrayList<>();
        for (Certificate c : ks.getCertificateChain(alias)) {
            certs.add((X509Certificate) c);
        }

        ApkSigner.SignerConfig signer =
            new ApkSigner.SignerConfig.Builder(alias, key, certs).build();
        new ApkSigner.Builder(Collections.singletonList(signer))
            .setInputApk(in)
            .setOutputApk(out)
            .setV1SigningEnabled(false)
            .setV2SigningEnabled(true)
            .build()
            .sign();

        ApkVerifier.Result r = new ApkVerifier.Builder(out).build().verify();
        if (!r.isVerified()) {
            System.err.println("Signature verification FAILED");
            r.getErrors().forEach(e -> System.err.println("  " + e));
            System.exit(1);
        }
        System.out.println("Signed + verified " + out
            + " (v1=" + r.isVerifiedUsingV1Scheme()
            + ", v2=" + r.isVerifiedUsingV2Scheme() + ")");
    }
}
