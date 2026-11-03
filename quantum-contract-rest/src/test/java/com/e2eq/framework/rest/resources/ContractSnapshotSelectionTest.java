package com.e2eq.framework.rest.resources;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ContractSnapshotSelectionTest {
    @TempDir Path directory;
    String spec(String name,String version) throws Exception {
        Path file=directory.resolve(name);
        Files.writeString(file,"openapi: 3.1.0\ninfo: {title: Test, version: '"+version+"'}\npaths: {}\n");
        return file.toString();
    }
    ContractHashResource resource(String location,String defaultVersion,Map<String,ContractDiscoveryConfig.Snapshot> snapshots) {
        var resource=new ContractHashResource();resource.openapiLocation=Optional.ofNullable(location);
        resource.discovery=new ContractDiscoveryConfig() {
            public Optional<String> defaultVersion(){return Optional.ofNullable(defaultVersion);}
            public Map<String,Snapshot> snapshots(){return snapshots;}
        };
        return resource;
    }
    ContractDiscoveryConfig.Snapshot snapshot(String version,String location) {
        return new ContractDiscoveryConfig.Snapshot() {
            public String version(){return version;}
            public Optional<String> location(){return Optional.ofNullable(location);}
        };
    }
    @SuppressWarnings("unchecked") Map<String,Object> body(jakarta.ws.rs.core.Response response) {
        assertEquals(200,response.getStatus());return (Map<String,Object>)response.getEntity();
    }
    @Test void unconfiguredVersioningPreservesExistingDefaultAndExactCurrentSelection() throws Exception {
        var resource=resource(spec("current.yaml","3.2.0"),null,Map.of());
        assertEquals(body(resource.get()),body(resource.getForVersion("3.2.0")));
        assertEquals(404,resource.getForVersion("3.3.0").getStatus());
    }
    @Test void explicitSnapshotSelectionNeverContaminatesTheLegacyDefaultCache() throws Exception {
        String legacy=spec("legacy.yaml","3.2.0"),current=spec("current.yaml","3.3.0");
        var original=body(resource(legacy,null,Map.of()).get());
        var resource=resource(current,"3.2.0",Map.of("legacy",snapshot("3.2.0",legacy),"current",snapshot("3.3.0",current)));
        assertEquals(original,body(resource.get()));
        assertEquals("3.3.0",body(resource.getForVersion("3.3.0")).get("contract_version"));
        assertEquals(original,body(resource.get()));
        assertEquals(404,resource.getForVersion("0.0.0").getStatus());
    }
    @Test void undeclaredAndMislabeledDefaultSnapshotsFailExplicitly() throws Exception {
        String current=spec("current.yaml","3.3.0");
        assertThrows(IllegalStateException.class,()->resource(current,"3.2.0",Map.of()).get());
        var mislabeled=resource(current,"3.2.0",Map.of("legacy",snapshot("3.2.0",current)));
        assertThrows(IllegalStateException.class,mislabeled::get);
    }
    @Test void duplicateVersionsAndInvalidSelectorsDoNotChooseAnotherSnapshot() throws Exception {
        String current=spec("current.yaml","3.3.0");
        var duplicate=resource(current,null,Map.of("one",snapshot("3.3.0",current),"two",snapshot("3.3.0",current)));
        assertThrows(IllegalStateException.class,duplicate::get);
        var resource=resource(current,null,Map.of());
        assertEquals(400,resource.getForVersion("").getStatus());
        assertEquals(400,resource.getForVersion(" 3.3.0").getStatus());
        assertEquals(404,resource.getForVersion("../../not-a-configured-path").getStatus());
    }
    @Test void emptyLocationsAreNotDeclaredAndNoContractStillReturns404() {
        var resource=resource(null,null,Map.of("off",snapshot("3.2.0",null)));
        assertEquals(404,resource.get().getStatus());
        assertEquals(404,resource.getForVersion("3.2.0").getStatus());
    }
}
