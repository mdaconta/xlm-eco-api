"""Bounded, non-billable Console connection recovery using existing XLM RPCs."""
import logging
import threading
import grpc
import xlm_eco_api_pb2 as pb

LOG = logging.getLogger('xlm.console.lifecycle')
TRANSIENT = {grpc.StatusCode.UNAVAILABLE, grpc.StatusCode.DEADLINE_EXCEEDED, grpc.StatusCode.CANCELLED}


class ConsoleSession:
    def __init__(self, stub, client_id, chat_provider, admin=None, interval=2, timeout=3):
        self.stub = stub
        self.client_id = client_id
        self.chat_provider = chat_provider
        self.admin = admin
        self.interval = interval
        self.timeout = timeout
        self._lock = threading.RLock()
        self._stop = threading.Event()
        self._thread = None
        self._preferences_pending = True
        self._chat_available = True
        self._generation = 0
        self._states = {name: {'state': 'connecting', 'code': ''}
                        for name in ('inference', 'administration', 'chat')}
        if admin is None:
            self._states['administration'] = {'state': 'unconfigured', 'code': ''}

    def _state(self, component, state, code=''):
        value = {'state': state, 'code': code}
        if self._states[component] != value:
            previous = self._states[component]['state']
            self._states[component] = value
            # All fields are internal constants / finite gRPC enums. Never log details/metadata.
            LOG.info('console_lifecycle component=%s previous=%s state=%s code=%s',
                     component, previous, state, code or 'NONE')

    def status(self):
        with self._lock:
            return {**{name: dict(value) for name, value in self._states.items()},
                    'generation': self._generation}

    def _failure(self, component, error):
        code = error.code()
        if code in TRANSIENT:
            state = 'disconnected'
        elif code in (grpc.StatusCode.UNAUTHENTICATED, grpc.StatusCode.PERMISSION_DENIED):
            state = 'authentication_error'
        else:
            state = 'configuration_error'
        self._state(component, state, code.name)

    def _probe(self):
        try:
            capabilities = self.stub.getProviderCapabilities(pb.ProviderRequest(
                client_id=self.client_id, provider=self.chat_provider), timeout=self.timeout)
            self._chat_available = bool(capabilities.capabilities.get('chat', False))
            if not self._chat_available:
                self._preferences_pending = True
                self._state('chat', 'configuration_error', 'CHAT_UNAVAILABLE')
            return True
        except grpc.RpcError as error:
            # This exact service-owned error precedes provider lookup; no raw detail is emitted.
            if error.code() == grpc.StatusCode.FAILED_PRECONDITION and error.details() == 'Client is not registered':
                return False
            if error.code() == grpc.StatusCode.NOT_FOUND:
                # Registration exists, but the configured text provider needs operator repair.
                self._chat_available = False
                self._preferences_pending = True
                self._state('chat', 'configuration_error', 'NOT_FOUND')
                return True
            raise

    def check_inference(self):
        """Serialize probes/re-registration; never invoke or replay a paid inference."""
        with self._lock:
            try:
                if not self._probe():
                    self._preferences_pending = True
                    self._state('inference', 'registering')
                    LOG.info('console_lifecycle event=re_registration_started')
                    self.stub.registerClient(pb.ClientRegistrationRequest(
                        client_name='flask-client-1', client_id=self.client_id), timeout=self.timeout)
                    # A duplicate response can follow an earlier committed, timed-out registration.
                    if not self._probe():
                        self._state('inference', 'configuration_error', 'REGISTRATION_REJECTED')
                        return False
                    LOG.info('console_lifecycle event=re_registration_completed')
                if self._preferences_pending and self._chat_available:
                    self._restore_preferences()
                if self._states['inference']['state'] != 'ready':
                    self._generation += 1
                    LOG.info('console_lifecycle event=recovered generation=%d', self._generation)
                self._state('inference', 'ready')
                return True
            except grpc.RpcError as error:
                self._failure('inference', error)
                return False

    def _restore_preferences(self):
        try:
            result = self.stub.setPreferredProviders(pb.ProviderSelectionRequest(
                client_id=self.client_id, provider_capabilities={self.chat_provider:
                    pb.ProviderCapabilitiesRequest(capabilities=['chat'])}), timeout=self.timeout)
            if result.success:
                self._preferences_pending = False
                self._state('chat', 'ready')
            else:
                self._state('chat', 'configuration_error', 'PREFERENCE_REJECTED')
        except grpc.RpcError as error:
            # An unusable startup text preference must not block Admin or explicit image requests.
            self._failure('chat', error)
            if error.code() in TRANSIENT:
                raise

    def check(self):
        self.check_inference()
        if self.admin is not None:
            try:
                self.admin.call('getStatus', pb.EmptyRequest(), timeout=self.timeout)
                with self._lock:
                    self._state('administration', 'ready')
            except grpc.RpcError as error:
                with self._lock:
                    self._failure('administration', error)
        return self.status()

    def start(self):
        with self._lock:
            if self._thread is not None:
                return
            self._thread = threading.Thread(target=self._monitor, name='xlm-console-recovery', daemon=True)
            self._thread.start()

    def _monitor(self):
        while not self._stop.is_set():
            self.check()
            self._stop.wait(self.interval)

    def close(self):
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=self.timeout * 5 + 1)
