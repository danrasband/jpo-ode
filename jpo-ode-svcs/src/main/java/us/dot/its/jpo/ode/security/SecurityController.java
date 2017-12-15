package us.dot.its.jpo.ode.security;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.KeyStore.Entry;
import java.security.KeyStore.PrivateKeyEntry;
import java.security.KeyStore.ProtectionParameter;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.util.Enumeration;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;

import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Controller;

import com.oss.asn1.EncodeFailedException;
import com.oss.asn1.EncodeNotSupportedException;
import com.safenetinc.luna.LunaSlotManager;
import com.safenetinc.luna.provider.LunaCertificateX509;

import gov.usdot.asn1.generated.ieee1609dot2.ieee1609dot2.CertificateType;
import gov.usdot.cv.security.cert.CertificateManager;
import gov.usdot.cv.security.cert.CertificateWrapper;
import gov.usdot.cv.security.crypto.CryptoProvider;
import us.dot.its.jpo.ode.OdeProperties;
import us.dot.its.jpo.ode.util.CodecUtils;

@Controller
public class SecurityController {

   private static final int CERTIFICATE_VERSION = 3;

   private Logger logger = LoggerFactory.getLogger(this.getClass());

   private String cryptoProvider;
   private KeyStore keyStore;

   private AlgorithmParameters parameters;

   private Certificate enrollmentCert;

   private static final String LUNA_PROVIDER = "LunaProvider";

   @Autowired
   protected SecurityController(OdeProperties odeProps) {
      super();

      Executors.newSingleThreadExecutor().submit(new CertificateLoader(odeProps));
   }

   @Bean
   KeyStore keyStore(
      @Value("${ode.keyStoreProvider}") String keystoreProvider,
      @Value("${ode.hsmSlotNumber}") String slot,
      @Value("${ode.hsmTokenPassword}") String password) throws EncodeFailedException, gov.usdot.cv.security.cert.CertificateException, EncodeNotSupportedException {

      try {

         /*
          * Note: could also use a keystore file, which contains the token label
          * or slot no. to use. Load that via "new FileInputStream(ksFileName)"
          * instead of ByteArrayInputStream. Save objects to the keystore via a
          * FileOutputStream.
          */

         ByteArrayInputStream is1 = new ByteArrayInputStream(("slot:" + slot).getBytes());
         keyStore = KeyStore.getInstance(keystoreProvider);
         keyStore.load(is1, password.toCharArray());

         Enumeration<String> aliases = keyStore.aliases();

         while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
//            LunaCertificateX509 cert = (LunaCertificateX509) keyStore.getCertificate(alias);
//            if (null != cert) {
//               byte[] certBytes = cert.getEncoded();
//               logger.debug("Certificate {}: {}", alias, CodecUtils.toHex(certBytes));
//
//               CertificateWrapper certificateWrapper = CertificateWrapper.fromBytes(
//                  new CryptoProvider(), certBytes);
//               CertificateManager.put(alias, certificateWrapper);
//            }
            logger.info("Key alias: {}", alias);
         }
      } catch (KeyStoreException kse) {
         logger.error("Unable to create keystore object", kse);
      } catch (NoSuchAlgorithmException nsae) {
         logger.error("Unexpected NoSuchAlgorithmException while loading keystore", nsae);
      } catch (CertificateException e) {
         logger.error("Unexpected CertificateException while loading keystore", e);
      } catch (IOException e) {
         // this should never happen
         logger.error("Unexpected IOException while loading keystore.", e);
      }
      return keyStore;

   }

   @Bean
   @DependsOn("encryptionCipher")
   Cipher decryptionCipher(KeyPair keyPair) throws GeneralSecurityException {
      Cipher cipher = Cipher.getInstance("ECIES", this.cryptoProvider);
      cipher.init(Cipher.DECRYPT_MODE, keyPair.getPrivate(), parameters);
      return cipher;
   }

   @Bean
   Cipher encryptionCipher(KeyPair keyPair) throws GeneralSecurityException {
      Cipher cipher = Cipher.getInstance("ECIES", this.cryptoProvider);
      cipher.init(Cipher.ENCRYPT_MODE, keyPair.getPublic());
      parameters = cipher.getParameters();
      return cipher;
   }

   @Bean
   @DependsOn("slotManager")
   KeyPair keyPair(
      @Value("${ode.hsmKeyPairAlias}") String hsmKeyPairAlias,
      @Value("${ode.hsmTokenPassword}") String password) throws GeneralSecurityException {

      // password can be a dummy char array as it will be ignored but, we'll use
      // the available password config property nonetheless
      KeyStore.ProtectionParameter param = new KeyStore.PasswordProtection(password.toCharArray());
      PrivateKeyEntry prKE = (PrivateKeyEntry) keyStore.getEntry(hsmKeyPairAlias, param);
      enrollmentCert = prKE.getCertificate();

      PublicKey pubKey = enrollmentCert.getPublicKey();
      if (pubKey != null)
         logger.info("Public Key: {}", CodecUtils.toHex(pubKey.getEncoded()));
      else
         logger.info("Public Key: {}", pubKey);

      return new KeyPair(pubKey, prKE.getPrivateKey());

      // KeyPairGenerator keyPairGenerator =
      // KeyPairGenerator.getInstance("ECDSA", this.cryptoProvider);
      // ECGenParameterSpec ecSpec = new ECGenParameterSpec("prime256v1");
      // keyPairGenerator.initialize(ecSpec);
      // return keyPairGenerator.generateKeyPair();
   }

   @Bean
   @DependsOn("keyPair")
   Certificate enrollmentCert() {
      return this.enrollmentCert;
   }

   @Bean
   Signature signingSignature(KeyPair keyPair) throws GeneralSecurityException {
      Signature signature = Signature.getInstance("SHA256withECDSA");
      signature.initSign(keyPair.getPrivate());
      return signature;
   }

   @Bean(destroyMethod = "logout")
   @DependsOn("keyStore")
   LunaSlotManager slotManager(
      @Value("${ode.hsmTokenLabel}") String tokenLabel,
      @Value("${ode.hsmTokenPassword}") String password,
      @Value("${ode.cryptoProvider}") String cryptoProvider) {
      this.cryptoProvider = cryptoProvider;
      LunaSlotManager slotManager;
      if (cryptoProvider.equals(LUNA_PROVIDER)) {
         try {
            slotManager = LunaSlotManager.getInstance();
            slotManager.login(tokenLabel, password);
            java.security.Provider provider = new com.safenetinc.luna.provider.LunaProvider();
            if (java.security.Security.getProvider(provider.getName()) == null) {
               // removing the provider is only necessary if it is already
               // registered
               // and you want to change its position
               // java.security.Security.removeProvider(provider.getName());
               java.security.Security.addProvider(provider);
               com.safenetinc.luna.LunaSlotManager.getInstance().logout();
            }
         } catch (Exception e) {
            logger.error("Exception caught during loading of the providers.", e);
            throw e;
         }
      } else {
         throw new IllegalArgumentException("ode.cryptoProvider property not defined");
      }
      return slotManager;
   }

   @Bean
   Signature verificationSignature(KeyPair keyPair) throws GeneralSecurityException {
      Signature signature = Signature.getInstance("SHA256withECDSA");
      signature.initVerify(keyPair.getPublic());
      return signature;
   }
}
