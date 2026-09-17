package us.daconta.xlmeco.security;

import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.util.NetUtil;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;

/** Validates the address actually bound; host names and DNS resolution are never used. */
public final class ListenerSecurity {
    private ListenerSecurity() {}

    public static NettyServerBuilder builder(String numericAddress, int port, Path certificate, Path privateKey) {
        if (numericAddress == null || numericAddress.contains("%") || port < 0 || port > 65535
                || !(NetUtil.isValidIpV4Address(numericAddress) || NetUtil.isValidIpV6Address(numericAddress)))
            throw new IllegalArgumentException("Listener requires a numeric IP address and valid port");
        try {
            InetAddress address = InetAddress.getByAddress(NetUtil.createByteArrayFromIpAddressString(numericAddress));
            if ((certificate == null) != (privateKey == null))
                throw new IllegalArgumentException("Listener TLS requires both certificate and private key");
            if (!address.isLoopbackAddress() && certificate == null)
                throw new IllegalArgumentException("Non-loopback listeners require TLS");
            NettyServerBuilder builder = NettyServerBuilder.forAddress(new InetSocketAddress(address, port));
            if (certificate != null) builder.useTransportSecurity(certificate.toFile(), privateKey.toFile());
            return builder;
        } catch (java.net.UnknownHostException e) {
            throw new IllegalArgumentException("Invalid numeric listener address");
        } catch (IllegalArgumentException e) {
            // Netty parsing errors can include private filesystem information: expose no causes.
            throw new IllegalArgumentException("Invalid listener address or TLS configuration");
        }
    }
}
