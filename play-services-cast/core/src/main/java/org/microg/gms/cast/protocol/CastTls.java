/*
 * SPDX-FileCopyrightText: 2025 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.cast.protocol;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.GeneralSecurityException;
import java.security.KeyManagementException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Helper to open the TLS channel to a Cast device.
 * <p>
 * Cast devices use self signed certificates that are generated on the fly and are not part of any
 * public key infrastructure, therefore the default {@link SSLContext} of the platform cannot be
 * used. The channel is unauthenticated at the transport layer, but the device identity is
 * established by the Cast protocol exchange itself. Callers that want additional safety can pin the
 * certificate fingerprint observed by a previous connection via
 * {@link #createSocket(String, int, String)}.
 */
public final class CastTls {
    private CastTls() {
    }

    /**
     * Opens a TLS connection to the given host, accepting any certificate.
     */
    public static SSLSocket createSocket(String host, int port) throws IOException {
        return createSocket(host, port, CastProtocol.CONNECT_TIMEOUT_MS, null);
    }

    /**
     * Opens a TLS connection to the given host, verifying the SHA-256 fingerprint of the device
     * certificate.
     *
     * @param expectedSha256 lowercase hex encoded SHA-256 fingerprint of the device certificate, or
     *                       {@code null} to accept any certificate.
     */
    public static SSLSocket createSocket(String host, int port, String expectedSha256) throws IOException {
        return createSocket(host, port, CastProtocol.CONNECT_TIMEOUT_MS, expectedSha256);
    }

    /**
     * Opens a TLS connection to the given host.
     *
     * @param timeout        timeout in milliseconds for connecting and for the TLS handshake.
     * @param expectedSha256 lowercase hex encoded SHA-256 fingerprint of the device certificate, or
     *                       {@code null} to accept any certificate.
     */
    public static SSLSocket createSocket(String host, int port, int timeout, String expectedSha256) throws IOException {
        if (host == null || host.length() == 0) {
            throw new IOException("No host given");
        }
        int effectivePort = port > 0 ? port : CastProtocol.DEFAULT_PORT;
        Socket plainSocket = new Socket();
        try {
            plainSocket.connect(new InetSocketAddress(host, effectivePort), timeout);
            plainSocket.setSoTimeout(timeout);
            plainSocket.setTcpNoDelay(true);
            SSLSocketFactory factory = createSocketFactory(expectedSha256);
            SSLSocket sslSocket = (SSLSocket) factory.createSocket(plainSocket, host, effectivePort, true);
            sslSocket.setUseClientMode(true);
            sslSocket.startHandshake();
            sslSocket.setSoTimeout(0);
            return sslSocket;
        } catch (SocketTimeoutException e) {
            closeQuietly(plainSocket);
            throw new IOException("Timed out connecting to " + host + ":" + effectivePort, e);
        } catch (IOException e) {
            closeQuietly(plainSocket);
            throw e;
        }
    }

    /**
     * Creates a socket factory that accepts the certificates of Cast devices.
     *
     * @param expectedSha256 optional pinned certificate fingerprint.
     */
    public static SSLSocketFactory createSocketFactory(String expectedSha256) throws IOException {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new CastTrustManager(expectedSha256)}, null);
            return context.getSocketFactory();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("TLS is not supported by this platform", e);
        } catch (KeyManagementException e) {
            throw new IOException("Could not initialize TLS", e);
        }
    }

    /**
     * Computes the SHA-256 fingerprint of a certificate, matching the format accepted by
     * {@link #createSocket(String, int, String)}.
     */
    public static String fingerprint(X509Certificate certificate) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                builder.append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        } catch (GeneralSecurityException e) {
            return null;
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static final class CastTrustManager implements X509TrustManager {
        private final String expectedSha256;

        CastTrustManager(String expectedSha256) {
            this.expectedSha256 = expectedSha256 == null ? null : expectedSha256.toLowerCase();
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("Client certificates are not used by Cast devices");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            if (expectedSha256 == null) {
                return;
            }
            if (chain == null || chain.length == 0) {
                throw new CertificateException("Device did not present a certificate");
            }
            String actual = fingerprint(chain[0]);
            if (!expectedSha256.equals(actual)) {
                throw new CertificateException("Device certificate fingerprint mismatch: " + actual);
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
