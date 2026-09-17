package us.daconta.xlmeco.admin;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import us.daconta.xlmeco.GrpcXlmServer;
import us.daconta.xlmeco.grpc.*;
import java.util.*;
import java.util.function.Supplier;

/** Authorization is applied to the whole service by AdminAuthInterceptor at bootstrap. */
public final class AdminService extends XlmAdminServiceGrpc.XlmAdminServiceImplBase {
    private final ProviderRegistry registry;
    public AdminService(ProviderRegistry registry) { this.registry=registry; }
    private static <T> void call(StreamObserver<T> o,Supplier<T> action) {
        try { o.onNext(action.get()); o.onCompleted(); }
        catch(ProviderRegistry.Invalid e) {
            Status s=switch(e.kind) { case INVALID -> Status.INVALID_ARGUMENT; case NOT_FOUND -> Status.NOT_FOUND;
                case PRECONDITION -> Status.FAILED_PRECONDITION; case STALE -> Status.ABORTED; };
            o.onError(s.withDescription(e.getMessage()).asRuntimeException());
        } catch(RuntimeException e) { o.onError(Status.INVALID_ARGUMENT.withDescription("Invalid administrative request").asRuntimeException()); }
    }
    private static AdminRevision revision(ProviderRegistry.Snapshot s) { return AdminRevision.newBuilder().setRevision(s.state().revision()).build(); }
    private static AdminProvider provider(ProviderRegistry.Snapshot s,ProviderRegistry.Provider p) {
        return AdminProvider.newBuilder().setProvider(p.id()).setEnabled(p.enabled())
                .addAllCapabilities(new TreeSet<>(ProviderRegistry.capabilities(p.id())))
                .putAllConfiguration(p.configuration()).setCredentialPresent(s.credentials().getOrDefault(p.id(),false))
                .setRevision(s.state().revision()).build();
    }
    private static AdminModel model(ProviderRegistry.Model m) {
        return AdminModel.newBuilder().setProvider(m.provider()).setModel(m.id()).setEnabled(m.enabled())
                .addAllCapabilities(new TreeSet<>(m.capabilities())).setDisplayName(m.displayName()).build();
    }
    @Override public void listProviders(AdminListProvidersRequest r,StreamObserver<AdminProviders> o) {
        call(o,()-> {
            if(!r.getCapability().isEmpty())ProviderRegistry.checkCapability(r.getCapability());
            var s=registry.snapshot(); var result=AdminProviders.newBuilder().setRevision(s.state().revision());
            s.state().providers().values().stream().sorted(Comparator.comparing(ProviderRegistry.Provider::id))
                .filter(p->r.getCapability().isEmpty()||ProviderRegistry.capabilities(p.id()).contains(r.getCapability()))
                .forEach(p->result.addProviders(provider(s,p))); return result.build();
        });
    }
    @Override public void getProvider(AdminProviderRequest r,StreamObserver<AdminProvider> o) {
        call(o,()-> { var s=registry.snapshot(); var p=s.state().providers().get(r.getProvider());
            if(p==null)throw new ProviderRegistry.Invalid(ProviderRegistry.Invalid.Kind.NOT_FOUND,"Provider not found");
            return provider(s,p); });
    }
    @Override public void updateProvider(AdminUpdateProviderRequest r,StreamObserver<AdminRevision> o) {
        call(o,()->revision(registry.updateProvider(r.getExpectedRevision(),r.getProvider(),r.hasEnabled()?r.getEnabled():null,r.getConfigurationMap())));
    }
    @Override public void listModels(AdminListModelsRequest r,StreamObserver<AdminModels> o) {
        call(o,()-> { var s=registry.snapshot(); if(!r.getCapability().isEmpty())ProviderRegistry.checkCapability(r.getCapability());
            if(!r.getProvider().isEmpty()&&!s.state().providers().containsKey(r.getProvider()))throw new ProviderRegistry.Invalid(ProviderRegistry.Invalid.Kind.NOT_FOUND,"Provider not found");
            var result=AdminModels.newBuilder().setRevision(s.state().revision());
            s.state().models().values().stream().sorted(Comparator.comparing(m->ProviderRegistry.key(m.provider(),m.id())))
                .filter(m->r.getProvider().isEmpty()||r.getProvider().equals(m.provider()))
                .filter(m->r.getCapability().isEmpty()||m.capabilities().contains(r.getCapability()))
                .forEach(m->result.addModels(model(m))); return result.build(); });
    }
    @Override public void upsertModel(AdminUpsertModelRequest r,StreamObserver<AdminRevision> o) {
        call(o,()-> { var m=r.getModel();
            if(m.getCapabilitiesCount()!=new HashSet<>(m.getCapabilitiesList()).size()) throw new ProviderRegistry.Invalid(ProviderRegistry.Invalid.Kind.INVALID,"Duplicate capability");
            return revision(registry.upsertModel(r.getExpectedRevision(),new ProviderRegistry.Model(m.getProvider(),m.getModel(),m.getEnabled(),new HashSet<>(m.getCapabilitiesList()),m.getDisplayName()))); });
    }
    @Override public void getDefaults(EmptyRequest r,StreamObserver<AdminDefaults> o) {
        call(o,()-> { var s=registry.snapshot(); var result=AdminDefaults.newBuilder().setRevision(s.state().revision());
            new TreeMap<>(s.state().defaults()).forEach((c,v)->result.addDefaults(AdminDefault.newBuilder().setCapability(c).setProvider(v.provider()).setModel(v.model())));
            return result.build(); });
    }
    @Override public void setDefault(AdminSetDefaultRequest r,StreamObserver<AdminRevision> o) {
        call(o,()-> { var d=r.getSelection(); if(r.getClear()&&(!d.getProvider().isEmpty()||!d.getModel().isEmpty()))throw new ProviderRegistry.Invalid(ProviderRegistry.Invalid.Kind.INVALID,"Clear default must omit selection");
            return revision(registry.setDefault(r.getExpectedRevision(),d.getCapability(),r.getClear()?null:new ProviderRegistry.Selection(d.getProvider(),d.getModel()))); });
    }
    @Override public void reloadCredentials(AdminRevision r,StreamObserver<AdminRevision> o) { call(o,()->revision(registry.reload(r.getRevision()))); }
    @Override public void getStatus(EmptyRequest r,StreamObserver<AdminStatus> o) {
        call(o,()->AdminStatus.newBuilder().setServerVersion(GrpcXlmServer.version).setSchemaVersion(1).setRevision(registry.snapshot().state().revision()).setReady(true).build());
    }
}
