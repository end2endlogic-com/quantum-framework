package com.e2eq.framework.model.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A realm assignment that grants no roles holds an empty role list, never null, however it is
 * built or read: the directory contract requires {@code roles}, and one null would fail the whole
 * realms-for-user response.
 */
class UserRealmRoleRolesNeverNullTest {

   @Test
   void everyWayOfBuildingAnAssignmentWithoutRolesYieldsAnEmptyList() {
      assertEmpty(new UserRealmRole());
      assertEmpty(UserRealmRole.builder().userId("u").realmRefName("r").build());
      assertEmpty(UserRealmRole.builder().userId("u").realmRefName("r").roles(null).build());
      assertEmpty(new UserRealmRole("u", "u", "r", null, List.of("files"), null, null, null, null,
          UserRealmRole.STATUS_ACTIVE));

      UserRealmRole cleared = UserRealmRole.builder().roles(List.of("viewer")).build();
      cleared.setRoles(null);
      assertEmpty(cleared);
   }

   @Test
   void aGetterResultCanBeAddedToAndTheAdditionSticks() {
      UserRealmRole role = new UserRealmRole("u", "u", "r", null, null, null, null, null, null, null);
      role.getRoles().add("viewer");
      assertEquals(List.of("viewer"), role.getRoles());
   }

   @Test
   void jsonWithANullOrMissingRoleListReadsAndWritesAsEmpty() throws Exception {
      ObjectMapper json = new ObjectMapper();
      for (String body : List.of("{\"userId\":\"u\",\"realmRefName\":\"r\",\"roles\":null}",
                                 "{\"userId\":\"u\",\"realmRefName\":\"r\"}")) {
         UserRealmRole role = json.readValue(body, UserRealmRole.class);
         assertEmpty(role);
         assertTrue(json.readTree(json.writeValueAsBytes(role)).get("roles").isArray(), body);
      }
   }

   private static void assertEmpty(UserRealmRole role) {
      assertNotNull(role.getRoles(), "roles must never be null");
      assertTrue(role.getRoles().isEmpty());
   }
}
