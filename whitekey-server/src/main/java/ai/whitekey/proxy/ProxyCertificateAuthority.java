package ai.whitekey.proxy;

import ai.whitekey.config.ProxyProperties;
import ai.whitekey.policy.HttpPolicyValidator;
import ai.whitekey.service.SecretFileLoader;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import jakarta.annotation.PostConstruct;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ProxyCertificateAuthority {

    private static final Logger log = LoggerFactory.getLogger(ProxyCertificateAuthority.class);

    private static final String EC_ALGORITHM = "EC";
    private static final String EC_CURVE = "secp256r1";
    private static final String CA_SUBJECT = "CN=WhiteKey Proxy Root CA, O=WhiteKey";
    private static final Duration CA_VALIDITY = Duration.ofDays(3650);
    private static final Duration LEAF_VALIDITY = Duration.ofDays(30);
    private static final Duration LEAF_RENEW_WINDOW = Duration.ofDays(1);
    private static final Duration BACKDATE_SKEW = Duration.ofDays(1);
    private static final int MAX_CACHED_SSL_CONTEXTS = 512;

    private record CachedSslContext(SslContext sslContext, Instant expiresAt) {}

    private final Path caCertPath;
    private final Path caKeyPath;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, CachedSslContext> sslContexts = new ConcurrentHashMap<>();

    private X509Certificate caCertificate;
    private PrivateKey caPrivateKey;
    private String caCertificatePem;

    public ProxyCertificateAuthority(
            ProxyProperties properties,
            @Value(SecretFileLoader.DEFAULT_DATA_DIR_EXPRESSION) String dataDir) {
        Path baseDir = Path.of(dataDir);
        this.caCertPath = baseDir.resolve(properties.caCertFile());
        this.caKeyPath = baseDir.resolve(properties.caKeyFile());
    }

    @PostConstruct
    public void init() {
        try {
            boolean certExists = Files.exists(caCertPath) && Files.size(caCertPath) > 0;
            boolean keyExists = Files.exists(caKeyPath) && Files.size(caKeyPath) > 0;

            if (!certExists && keyExists && caCertPath.getFileName() != null) {
                Path legacyCertPath = caKeyPath.resolveSibling(caCertPath.getFileName());
                if (!legacyCertPath.equals(caCertPath)
                        && Files.exists(legacyCertPath)
                        && Files.size(legacyCertPath) > 0) {
                    if (caCertPath.getParent() != null) {
                        Files.createDirectories(caCertPath.getParent());
                    }
                    Files.move(legacyCertPath, caCertPath);
                    SecretFileLoader.setPosixPermissions(caCertPath, SecretFileLoader.PUBLIC_READ_PERMISSIONS);
                    certExists = true;
                    log.info("Migrated WhiteKey Proxy Root CA certificate from {} to {}", legacyCertPath, caCertPath);
                }
            }

            if (certExists != keyExists) {
                throw new IllegalStateException(
                        "Both proxy CA certificate (" + caCertPath + ") and private key ("
                                + caKeyPath + ") must exist together");
            }

            if (certExists) {
                loadExistingCa();
            } else {
                generateAndSaveCa();
            }
            this.caCertificatePem = toPem(this.caCertificate);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize WhiteKey proxy Certificate Authority", e);
        }
    }

    public SslContext sslContextForHost(String host) {
        String normalized = HttpPolicyValidator.normalizeHostIfValid(host);
        Instant renewThreshold = Instant.now().plus(LEAF_RENEW_WINDOW);
        CachedSslContext cached = sslContexts.get(normalized);
        if (cached != null && cached.expiresAt().isAfter(renewThreshold)) {
            return cached.sslContext();
        }
        CachedSslContext updated = sslContexts.compute(normalized, (key, existing) -> {
            if (existing != null && existing.expiresAt().isAfter(renewThreshold)) {
                return existing;
            }
            return createLeafSslContext(key);
        });
        if (sslContexts.size() > MAX_CACHED_SSL_CONTEXTS) {
            evictContexts(normalized, renewThreshold);
        }
        return updated.sslContext();
    }

    private void evictContexts(String keepHost, Instant renewThreshold) {
        sslContexts.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(renewThreshold));
        int excess = sslContexts.size() - MAX_CACHED_SSL_CONTEXTS;
        if (excess > 0) {
            sslContexts.entrySet().stream()
                    .filter(entry -> !entry.getKey().equals(keepHost))
                    .sorted(Comparator.comparing(entry -> entry.getValue().expiresAt()))
                    .limit(excess)
                    .map(Map.Entry::getKey)
                    .toList()
                    .forEach(sslContexts::remove);
        }
    }

    public String getCaCertificatePem() {
        return caCertificatePem;
    }

    private void loadExistingCa() throws GeneralSecurityException, IOException {
        try (InputStream in = Files.newInputStream(caCertPath)) {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            this.caCertificate = (X509Certificate) factory.generateCertificate(in);
        }
        try (PEMParser parser = new PEMParser(Files.newBufferedReader(caKeyPath, StandardCharsets.UTF_8))) {
            Object parsed = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
            if (parsed instanceof PEMKeyPair keyPair) {
                this.caPrivateKey = converter.getKeyPair(keyPair).getPrivate();
            } else if (parsed instanceof PrivateKeyInfo keyInfo) {
                this.caPrivateKey = converter.getPrivateKey(keyInfo);
            } else {
                throw new IllegalStateException("Unsupported CA private key format in " + caKeyPath);
            }
        }
        log.info("Loaded WhiteKey Proxy Root CA certificate from: {}", caCertPath);
    }

    private void generateAndSaveCa()
            throws GeneralSecurityException, OperatorCreationException, IOException {
        KeyPair caKeyPair = generateEcKeyPair();
        Instant now = Instant.now();
        Instant notBefore = now.minus(BACKDATE_SKEW);
        Instant notAfter = now.plus(CA_VALIDITY);
        X500Name subject = new X500Name(CA_SUBJECT);

        var builder = new JcaX509v3CertificateBuilder(
                subject,
                nextSerialNumber(),
                Date.from(notBefore),
                Date.from(notAfter),
                subject,
                caKeyPair.getPublic());
        JcaX509ExtensionUtils extUtils = new JcaX509ExtensionUtils();
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
        builder.addExtension(
                Extension.subjectKeyIdentifier, false, extUtils.createSubjectKeyIdentifier(caKeyPair.getPublic()));
        builder.addExtension(
                Extension.authorityKeyIdentifier, false, extUtils.createAuthorityKeyIdentifier(caKeyPair.getPublic()));

        this.caPrivateKey = caKeyPair.getPrivate();
        this.caCertificate = signCertificate(builder, this.caPrivateKey);

        try {
            SecretFileLoader.writeFile(
                    caCertPath, toPem(this.caCertificate), SecretFileLoader.PUBLIC_READ_PERMISSIONS);
            SecretFileLoader.writeFile(
                    caKeyPath,
                    toPem(new JcaPKCS8Generator(this.caPrivateKey, null).generate()),
                    SecretFileLoader.OWNER_READ_WRITE_PERMISSIONS);
            log.info("Generated WhiteKey Proxy Root CA certificate at: {}", caCertPath);
        } catch (IOException e) {
            log.warn("Using temporary in-memory Proxy Root CA (failed writing to {}): {}", caCertPath, e.getMessage());
        }
    }

    private CachedSslContext createLeafSslContext(String host) {
        try {
            KeyPair leafKeyPair = generateEcKeyPair();
            Instant now = Instant.now();
            Instant notBefore = now.minus(BACKDATE_SKEW);
            Instant notAfter = now.plus(LEAF_VALIDITY);

            X500Name issuer = X500Name.getInstance(caCertificate.getSubjectX500Principal().getEncoded());
            X500Name subject = new X500Name("CN=" + host);

            var builder = new JcaX509v3CertificateBuilder(
                    issuer,
                    nextSerialNumber(),
                    Date.from(notBefore),
                    Date.from(notAfter),
                    subject,
                    leafKeyPair.getPublic());

            JcaX509ExtensionUtils extUtils = new JcaX509ExtensionUtils();
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
            builder.addExtension(
                    Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
            int sanType = HttpPolicyValidator.isIpAddress(host)
                    ? GeneralName.iPAddress
                    : GeneralName.dNSName;
            builder.addExtension(
                    Extension.subjectAlternativeName,
                    false,
                    new GeneralNames(new GeneralName(sanType, host)));
            builder.addExtension(
                    Extension.subjectKeyIdentifier,
                    false,
                    extUtils.createSubjectKeyIdentifier(leafKeyPair.getPublic()));
            builder.addExtension(
                    Extension.authorityKeyIdentifier, false, extUtils.createAuthorityKeyIdentifier(caCertificate));

            X509Certificate leafCert = signCertificate(builder, caPrivateKey);

            SslContext sslContext = SslContextBuilder.forServer(leafKeyPair.getPrivate(), leafCert, caCertificate)
                    .protocols("TLSv1.3", "TLSv1.2")
                    .applicationProtocolConfig(new ApplicationProtocolConfig(
                            ApplicationProtocolConfig.Protocol.ALPN,
                            ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                            ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                            ApplicationProtocolNames.HTTP_1_1))
                    .build();
            return new CachedSslContext(sslContext, notAfter);
        } catch (Exception e) {
            throw new IllegalStateException("Failed creating TLS context for host " + host, e);
        }
    }

    private KeyPair generateEcKeyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(EC_ALGORITHM);
        generator.initialize(new ECGenParameterSpec(EC_CURVE), secureRandom);
        return generator.generateKeyPair();
    }

    private BigInteger nextSerialNumber() {
        return new BigInteger(127, secureRandom).add(BigInteger.ONE);
    }

    private static X509Certificate signCertificate(
            JcaX509v3CertificateBuilder builder, PrivateKey signingKey)
            throws GeneralSecurityException, OperatorCreationException {
        String signatureAlgorithm = "RSA".equalsIgnoreCase(signingKey.getAlgorithm())
                ? "SHA256withRSA"
                : "SHA256withECDSA";
        ContentSigner signer = new JcaContentSignerBuilder(signatureAlgorithm).build(signingKey);
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    private static String toPem(Object obj) throws IOException {
        StringWriter sw = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(sw)) {
            writer.writeObject(obj);
        }
        return sw.toString();
    }
}
