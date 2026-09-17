"""Shared authenticated management transport. No credentials are returned to browsers."""
import ipaddress
from pathlib import Path
import grpc


def channel(endpoint, ca_file=None):
    """Plaintext is allowed only for explicit numeric loopback targets."""
    try:
        host, port = endpoint.rsplit(':', 1)
        if not 1 <= int(port) <= 65535:
            raise ValueError()
        host = host.strip('[]')
        if not host or any(c in host for c in '/@?#'):
            raise ValueError()
        try:
            loopback = ipaddress.ip_address(host).is_loopback
        except ValueError:
            loopback = False
        if ca_file:
            roots = Path(ca_file).read_bytes()
            if not roots:
                raise ValueError()
            return grpc.secure_channel(endpoint, grpc.ssl_channel_credentials(roots))
        if not loopback:
            raise ValueError()
        return grpc.insecure_channel(endpoint)
    except (ValueError, OSError, TypeError):
        raise ValueError('Invalid endpoint or TLS configuration; remote endpoints require a trusted CA file') from None


class AdminClient:
    def __init__(self, stub, token_file):
        try:
            token = Path(token_file).read_text(encoding='utf-8').strip()
            if len(token) < 32 or any(ord(c) < 33 or ord(c) > 126 for c in token):
                raise ValueError()
        except (ValueError, OSError, TypeError):
            raise ValueError('Admin bearer credential is unavailable or invalid') from None
        self._stub = stub
        self._metadata = (('authorization', 'Bearer ' + token),)

    def call(self, method, request, timeout=15):
        return getattr(self._stub, method)(request, timeout=timeout, metadata=self._metadata)
