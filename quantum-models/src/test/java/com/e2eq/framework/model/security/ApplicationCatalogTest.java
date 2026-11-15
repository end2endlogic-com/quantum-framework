package com.e2eq.framework.model.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The catalog fields on {@link Application} and the public {@link ApplicationCatalogEntry}. */
class ApplicationCatalogTest {

   private final ObjectMapper mapper = new ObjectMapper()
      .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

   @Test
   void packsWrittenBeforeTheCatalogFieldsStillLoad() throws Exception {
      // A slice-1 identity pack row: declaration only, none of the catalog fields.
      String legacy = """
         {"refName":"planner","displayName":"Planner",
          "roles":[{"name":"planner-user","displayName":"Planner user","grantableBy":["tenant-admin"]}],
          "ownerRoles":["planner-user"],"memberRoles":["planner-user"],
          "tenancy":{"placement":"POOLED","realms":[]},"uses":[]}
         """;
      Application application = mapper.readValue(legacy, Application.class);

      assertEquals("planner", application.getRefName());
      assertEquals(1, application.getRoles().size());
      assertNull(application.getAppVersion());
      assertNull(application.getOwner());
      assertNull(application.getLifecycleState());
      assertNull(application.getSignInRoute());
      assertTrue(application.getPackRefs().isEmpty());
      assertTrue(application.getLaunchAddresses().isEmpty());
      assertFalse(ApplicationCatalogEntry.catalogued(application), "no lifecycle state: not public");

      Application bare = mapper.readValue("{\"refName\":\"old-app\",\"displayName\":\"Old\"}", Application.class);
      assertEquals("old-app", bare.getRefName());
   }

   @Test
   void theCatalogFieldsRoundTrip() throws Exception {
      Application application = catalogued();
      application.setDataDomain(null);
      Application back = mapper.readValue(mapper.writeValueAsString(application), Application.class);
      assertEquals("2.3.0", back.getAppVersion());
      assertEquals(ApplicationLifecycleState.ACTIVE, back.getLifecycleState());
      assertEquals("https://planner.dev.example.test", back.getLaunchAddresses().get(0).getUrl());
      assertEquals(List.of("https://planner.dev.example.test/callback"), back.getSignInRoute().getCallbackUris());
   }

   @Test
   void thePublicEntryCarriesCatalogFieldsOnly() {
      JsonNode entry = mapper.valueToTree(ApplicationCatalogEntry.of(catalogued()));

      Set<String> fields = new TreeSet<>();
      entry.fieldNames().forEachRemaining(fields::add);
      assertEquals(new TreeSet<>(Set.of("refName", "displayName", "appVersion", "lifecycleState",
         "launchAddresses", "signIn")), fields);
      Set<String> signIn = new TreeSet<>();
      entry.get("signIn").fieldNames().forEachRemaining(signIn::add);
      assertEquals(new TreeSet<>(Set.of("clientId", "applicationEntryUrl", "authorizationPath")), signIn);
      String json = entry.toString();
      for (String secret : List.of("acme-platform-team", "pack-planner-2.3.0", "planner-user", "callback",
         "POOLED", "tenant-x")) {
         assertFalse(json.contains(secret), secret + " must not be public: " + json);
      }
   }

   @Test
   void onlyCataloguedLifecycleStatesArePublic() {
      Application application = catalogued();
      for (ApplicationLifecycleState state : ApplicationLifecycleState.values()) {
         application.setLifecycleState(state);
         boolean expected = state == ApplicationLifecycleState.PREVIEW || state == ApplicationLifecycleState.ACTIVE
            || state == ApplicationLifecycleState.DEPRECATED;
         assertEquals(expected, ApplicationCatalogEntry.catalogued(application), state.name());
      }
   }

   private static Application catalogued() {
      Application application = new Application();
      application.setRefName("planner");
      application.setDisplayName("Planner");
      application.setAppVersion("2.3.0");
      application.setOwner("acme-platform-team");
      application.setLifecycleState(ApplicationLifecycleState.ACTIVE);
      application.setPackRefs(new ArrayList<>(List.of("pack-planner-2.3.0")));
      application.setRoles(new ArrayList<>(List.of(ApplicationRole.builder().name("planner-user")
         .grantableBy(List.of("tenant-admin")).build())));
      application.setTenancy(ApplicationTenancy.builder().placement("POOLED").build());
      com.e2eq.framework.model.persistent.base.DataDomain domain = new com.e2eq.framework.model.persistent.base.DataDomain();
      domain.setTenantId("tenant-x");
      application.setDataDomain(domain);
      application.setLaunchAddresses(new ArrayList<>(List.of(
         ApplicationLaunchAddress.builder().environment("development").url("https://planner.dev.example.test").build())));
      application.setSignInRoute(ApplicationSignInRoute.builder()
         .clientId("planner-web")
         .applicationEntryUrl("https://planner.dev.example.test")
         .authorizationPath("/oauth/authorize")
         .callbackUris(new ArrayList<>(List.of("https://planner.dev.example.test/callback")))
         .build());
      return application;
   }
}
