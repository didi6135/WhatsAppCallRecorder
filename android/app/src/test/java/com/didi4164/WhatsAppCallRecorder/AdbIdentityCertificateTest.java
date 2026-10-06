package com.didi4164.WhatsAppCallRecorder;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;

public class AdbIdentityCertificateTest {
  @Test public void privateIdentitySurvivesStorageAndAuthenticatesWithItsCertificate() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair identity = generator.generateKeyPair();
    X509Certificate original = AdbIdentityCertificate.create(identity);
    original.checkValidity();
    original.verify(identity.getPublic());
    assertEquals(3, original.getVersion());
    assertEquals("SHA256withRSA", original.getSigAlgName());

    X509Certificate restored = (X509Certificate) CertificateFactory.getInstance("X.509")
        .generateCertificate(new ByteArrayInputStream(original.getEncoded()));
    Signature signing = Signature.getInstance("SHA256withRSA");
    signing.initSign(KeyFactory.getInstance("RSA")
        .generatePrivate(new PKCS8EncodedKeySpec(identity.getPrivate().getEncoded())));
    byte[] challenge = new byte[] {3, 1, 4, 1, 5};
    signing.update(challenge);
    byte[] proof = signing.sign();
    Signature verification = Signature.getInstance("SHA256withRSA");
    verification.initVerify(restored.getPublicKey());
    verification.update(challenge);
    assertTrue(verification.verify(proof));
  }
}
