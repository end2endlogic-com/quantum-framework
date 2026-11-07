package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.security.CredentialUserIdPassword;
import dev.morphia.Datastore;
import dev.morphia.query.Query;
import dev.morphia.query.filters.Filter;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import java.util.Date;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CredentialPasswordPreservationTest {
    @Test void metadataReplacementPreservesSecretAndRevocationState() { checkMissing(RefNameContract.WriteMode.REPLACE); }
    @Test void metadataMergePreservesSecretAndRevocationState() { checkMissing(RefNameContract.WriteMode.MERGE); }
    private void checkMissing(RefNameContract.WriteMode mode) {
        var stored = stored();
        var incoming = new CredentialUserIdPassword();
        incoming.setId(stored.getId());
        incoming.setRoles(new String[]{"user", "member"});
        incoming.setPasswordRevision(0L);
        new CredentialRepo().reconcileServerOwnedState(datastore(stored), incoming, mode);
        assertEquals(stored.getPasswordHash(), incoming.getPasswordHash());
        assertEquals(stored.getHashingAlgorithm(), incoming.getHashingAlgorithm());
        assertEquals(stored.getPasswordRevision(), incoming.getPasswordRevision());
        assertEquals(stored.getPasswordChangedAt(), incoming.getPasswordChangedAt());
        assertArrayEquals(new String[]{"user", "member"}, incoming.getRoles());
    }
    @Test void passwordChangeStillAdvancesRevision() {
        var stored = stored();
        var incoming = new CredentialUserIdPassword();
        incoming.setId(stored.getId());
        incoming.setPasswordHash("replacement-hash");
        incoming.setPasswordRevision(0L);
        new CredentialRepo().reconcileServerOwnedState(datastore(stored), incoming, RefNameContract.WriteMode.REPLACE);
        assertEquals("replacement-hash", incoming.getPasswordHash());
        assertEquals(stored.currentPasswordRevision()+1, incoming.currentPasswordRevision());
        assertTrue(incoming.getPasswordChangedAt().after(stored.getPasswordChangedAt()));
    }
    @Test void sameHashCannotRollBackRevision() {
        var stored=stored(); var incoming=new CredentialUserIdPassword();
        incoming.setId(stored.getId()); incoming.setPasswordHash(stored.getPasswordHash()); incoming.setPasswordRevision(0L);
        new CredentialRepo().reconcileServerOwnedState(datastore(stored),incoming,RefNameContract.WriteMode.REPLACE);
        assertEquals(stored.getPasswordRevision(),incoming.getPasswordRevision());
    }
    private CredentialUserIdPassword stored() {
        var value=new CredentialUserIdPassword(); value.setId(new ObjectId()); value.setPasswordHash("stored-hash");
        value.setHashingAlgorithm("legacy-algorithm"); value.setPasswordRevision(7L); value.setPasswordChangedAt(new Date(1000)); return value;
    }
    @SuppressWarnings("unchecked") private Datastore datastore(CredentialUserIdPassword stored) {
        Datastore ds=mock(Datastore.class); Query<CredentialUserIdPassword> q=mock(Query.class);
        when(ds.find(CredentialUserIdPassword.class)).thenReturn(q);
        when(q.filter(any(Filter[].class))).thenReturn(q); when(q.first()).thenReturn(stored); return ds;
    }
}
