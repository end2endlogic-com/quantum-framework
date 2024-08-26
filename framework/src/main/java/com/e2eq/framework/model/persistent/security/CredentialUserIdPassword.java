package com.e2eq.framework.model.persistent.security;

import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import dev.morphia.annotations.Entity;
import dev.morphia.annotations.IndexOptions;
import dev.morphia.annotations.Indexed;
import io.quarkus.runtime.annotations.RegisterForReflection;
import com.e2eq.framework.model.persistent.base.BaseModel;

import jakarta.validation.constraints.NotNull;

@Entity("credentialUserIdPassword")
@RegisterForReflection
public class CredentialUserIdPassword extends BaseModel {

    @Indexed(options= @IndexOptions(unique=true))
    @NotNull protected String userId;
    @NotNull protected String passwordHash;
    @NotNull protected String hashingAlgorithm="BCrypt.default";
    @NotNull protected Date lastUpdate;
    @NotNull protected String defaultRealm;
    @NotNull protected Map<String, String> area2RealmOverrides = new HashMap<>();
    @NotNull protected String[] roles= new String[0];

    public CredentialUserIdPassword() {
        super();
    }

    public String getUserId() {
        return userId;
    }
    public void setUserId(@NotNull String userId) {
        this.userId = userId;
    }

    public String getPasswordHash() {
        return passwordHash;
    }
    public void setPasswordHash(@NotNull String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getHashingAlgorithm() {
        return hashingAlgorithm;
    }
    public void setHashingAlgorithm(String hashingAlgorithm) {
        this.hashingAlgorithm = hashingAlgorithm;
    }

    public Date getLastUpdate() {return lastUpdate;}
    public void setLastUpdate(@NotNull Date lastUpdate) {this.lastUpdate = lastUpdate;}


    public String getDefaultRealm () {return defaultRealm;}
    public void setDefaultRealm (@NotNull String defaultRealm) {this.defaultRealm = defaultRealm;}

    public Map<String, String> getArea2RealmOverrides () {
        return area2RealmOverrides;
    }

    public void setArea2RealmOverrides (@NotNull Map<String, String> area2RealmOverrides) {
        this.area2RealmOverrides = area2RealmOverrides;
    }

    public String[] getRoles () {
        return roles;
    }
    public void setRoles (@NotNull String[] roles) {
        this.roles = roles;
    }

    @Override
    public String bmFunctionalArea() {
        return "SECURITY";
    }

    @Override
    public String bmFunctionalDomain() {
        return "CREDENTIAL_USERID_PASSWORD";
    }

    @Override
    public boolean equals (Object o) {
        if (this == o) return true;
        if (!(o instanceof CredentialUserIdPassword)) return false;
        if (!super.equals(o)) return false;

        CredentialUserIdPassword that = (CredentialUserIdPassword) o;

        if (userId != null ? !userId.equals(that.userId) : that.userId != null) return false;
        if (passwordHash != null ? !passwordHash.equals(that.passwordHash) : that.passwordHash != null) return false;
        if (hashingAlgorithm != null ? !hashingAlgorithm.equals(that.hashingAlgorithm) : that.hashingAlgorithm != null)
            return false;
        if (lastUpdate != null ? !lastUpdate.equals(that.lastUpdate) : that.lastUpdate != null) return false;
        if (defaultRealm != null ? !defaultRealm.equals(that.defaultRealm) : that.defaultRealm != null) return false;
        if (area2RealmOverrides != null ? !area2RealmOverrides.equals(that.area2RealmOverrides) :
               that.area2RealmOverrides != null)
            return false;
        // Probably incorrect - comparing Object[] arrays with Arrays.equals
        return Arrays.equals(roles, that.roles);
    }

    @Override
    public int hashCode () {
        int result = super.hashCode();
        result = 31 * result + (userId != null ? userId.hashCode() : 0);
        result = 31 * result + (passwordHash != null ? passwordHash.hashCode() : 0);
        result = 31 * result + (hashingAlgorithm != null ? hashingAlgorithm.hashCode() : 0);
        result = 31 * result + (lastUpdate != null ? lastUpdate.hashCode() : 0);
        result = 31 * result + (defaultRealm != null ? defaultRealm.hashCode() : 0);
        result = 31 * result + (area2RealmOverrides != null ? area2RealmOverrides.hashCode() : 0);
        result = 31 * result + Arrays.hashCode(roles);
        return result;
    }
}
