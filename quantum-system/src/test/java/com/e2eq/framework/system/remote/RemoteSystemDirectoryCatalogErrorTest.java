package com.e2eq.framework.system.remote;

import com.e2eq.framework.controlplane.api.DefaultEndpoint;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RemoteSystemDirectoryCatalogErrorTest {

    @Test
    void missingRealmIsEmpty() {
        DefaultEndpoint client = mock(DefaultEndpoint.class);
        when(client.findRealmByRefName("missing")).thenThrow(new NotFoundException());
        RemoteSystemDirectory directory = new RemoteSystemDirectory(client);

        assertTrue(directory.findRealmByRefName("missing").isEmpty());
    }

    @Test
    void clientErrorIsNotConvertedToInternalFailure() {
        DefaultEndpoint client = mock(DefaultEndpoint.class);
        WebApplicationException rejected = new WebApplicationException(
            Response.status(400).entity("X-Tenant-Id is required").build());
        when(client.findRealmByRefName("helixor-code-D1")).thenThrow(rejected);
        RemoteSystemDirectory directory = new RemoteSystemDirectory(client);

        WebApplicationException thrown = assertThrows(WebApplicationException.class,
            () -> directory.findRealmByRefName("helixor-code-D1"));
        assertEquals(400, thrown.getResponse().getStatus());
    }

    @Test
    void serverErrorRemainsFailLoud() {
        DefaultEndpoint client = mock(DefaultEndpoint.class);
        when(client.findRealmByRefName("helixor-code-D1")).thenThrow(
            new WebApplicationException(Response.status(500).build()));
        RemoteSystemDirectory directory = new RemoteSystemDirectory(client);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> directory.findRealmByRefName("helixor-code-D1"));
        assertTrue(thrown.getMessage().contains("HTTP 500"));
    }
}
