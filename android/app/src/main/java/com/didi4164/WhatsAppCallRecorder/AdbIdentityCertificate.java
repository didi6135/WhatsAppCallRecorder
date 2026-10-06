package com.didi4164.WhatsAppCallRecorder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import javax.security.auth.x500.X500Principal;

/** A standard RSA X.509 identity for ADB, without Android hidden certificate APIs. */
public final class AdbIdentityCertificate {
  private AdbIdentityCertificate() { }

  public static X509Certificate create(KeyPair keys) throws GeneralSecurityException {
    byte[] algorithm = new byte[] {0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86,
        0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x0b, 0x05, 0x00};
    byte[] name = new X500Principal("CN=Codaki Recorder").getEncoded();
    long now = System.currentTimeMillis();
    byte[] validity = sequence(time(now - 86400000L), time(now + 20L * 365 * 86400000L));
    byte[] serial = new BigInteger(159, new SecureRandom()).add(BigInteger.ONE).toByteArray();
    byte[] body = sequence(tag(0xa0, tag(0x02, new byte[] {2})), tag(0x02, serial),
        algorithm, name, validity, name, keys.getPublic().getEncoded());
    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initSign(keys.getPrivate()); signer.update(body);
    byte[] signature = signer.sign();
    byte[] bitString = new byte[signature.length + 1];
    System.arraycopy(signature, 0, bitString, 1, signature.length);
    X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
        .generateCertificate(new ByteArrayInputStream(sequence(body, algorithm, tag(0x03, bitString))));
    certificate.verify(keys.getPublic());
    return certificate;
  }

  private static byte[] time(long timestamp) {
    SimpleDateFormat format = new SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.US);
    format.setTimeZone(TimeZone.getTimeZone("UTC"));
    return tag(0x18, format.format(new Date(timestamp)).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
  }

  private static byte[] sequence(byte[]... parts) {
    ByteArrayOutputStream data = new ByteArrayOutputStream();
    for (byte[] part : parts) data.write(part, 0, part.length);
    return tag(0x30, data.toByteArray());
  }

  private static byte[] tag(int type, byte[] payload) {
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    result.write(type);
    if (payload.length < 128) result.write(payload.length);
    else {
      int count = 0, remaining = payload.length;
      while (remaining > 0) { count++; remaining >>>= 8; }
      result.write(0x80 | count);
      for (int i = count - 1; i >= 0; i--) result.write((payload.length >>> (8 * i)) & 255);
    }
    result.write(payload, 0, payload.length);
    return result.toByteArray();
  }
}
