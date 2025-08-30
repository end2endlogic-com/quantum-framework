package com.e2eq.framework.util;

import jakarta.enterprise.context.ApplicationScoped;
import lombok.Data;
import lombok.Getter;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
@Data
public class EnvConfigUtils {
   // system context values
   @ConfigProperty(name = "quantum.anonymousUserId", defaultValue = "anonymous@system.com"  )
   protected String anonymousUserId;

   @ConfigProperty(name = "quantum.realmConfig.defaultRealm", defaultValue = "mycompanyxyz-com"  )
   protected String defaultRealm;

   @ConfigProperty(name = "quantum.realmConfig.defaultTenantId", defaultValue = "mycompanyxyz.com"  )
   protected String defaultTenantId;

   @ConfigProperty(name = "quantum.realmConfig.defaultOrgRefName", defaultValue = "mycompanyxyz.com"  )
   @Getter
   protected String defaultOrgRefName;

   @ConfigProperty(name = "quantum.realmConfig.defaultAccountNumber", defaultValue = "9999999999"  )
   @Getter
   protected String defaultAccountNumber;

   @ConfigProperty(name = "quantum.realmConfig.systemOrgRefName", defaultValue = "system.com"  )
   @Getter
   protected  String systemOrgRefName;

   @ConfigProperty(name = "quantum.realmConfig.systemAccountNumber", defaultValue = "0000000000"  )
   @Getter
   protected  String systemAccountNumber;

   @ConfigProperty(name = "quantum.realmConfig.systemTenantId", defaultValue = "system.com"  )
   @Getter
   protected  String systemTenantId;

   @ConfigProperty(name = "quantum.realmConfig.systemRealm", defaultValue = "system-com"  )
   @Getter
   protected  String systemRealm;

   @ConfigProperty(name = "quantum.realmConfig.systemUserId", defaultValue = "system@system.com"  )
   @Getter
   protected  String systemUserId;

   @ConfigProperty(name = "quantum.realmConfig.testUserId", defaultValue = "test@test-system.com"  )
   @Getter
   protected String testUserId;

   @ConfigProperty(name = "quantum.realmConfig.defaultUserId", defaultValue = "test@mycompanyxyz.com"  )
   @Getter
   protected String defaultUserId;

   @ConfigProperty(name = "quantum.realmConfig.testOrgRefName", defaultValue = "test-system.com"  )
   @Getter
   protected String testOrgRefName;

   @ConfigProperty(name = "quantum.realmConfig.testAccountNumber", defaultValue = "0000000000"  )
   @Getter
   protected String testAccountNumber;

   @ConfigProperty(name = "quantum.realmConfig.testTenantId", defaultValue = "test-system.com"  )
   @Getter
   protected String testTenantId;

   @Getter
   @ConfigProperty(name = "quantum.realmConfig.testRealm", defaultValue = "test-system-com"  )
   protected String testRealm;

   protected final int defaultDataSegment = 0;

}
