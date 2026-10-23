package com.e2eq.framework.model.persistent.morphia.changesets;

import com.e2eq.framework.model.persistent.migration.base.ChangeSetBase;
import com.e2eq.framework.model.persistent.migration.refname.CanonicalRefNameMigrator;
import com.e2eq.framework.model.persistent.migration.refname.RefNameMigrationPlan;
import com.e2eq.framework.model.persistent.migration.refname.RefNameMigrationPlan.Kind;
import com.mongodb.client.MongoClient;
import dev.morphia.transactions.MorphiaSession;
import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Reports, per realm, the UserGroup and Policy refNames that predate the canonical rule and what
 * {@link CanonicalRefNameMigrator} would do with each. Changes nothing: renames and merges are applied per realm by an
 * operator ({@code POST /system/migration/refnames/{realm}/apply}) after reviewing this report or
 * {@code GET /system/migration/refnames/{realm}}. Applying is deliberately not a configuration switch on this change
 * set: a changed checksum would make the request filter refuse every realm until migrations were re-run there.
 */
@Startup
@ApplicationScoped
public class CanonicalizeRefNames extends ChangeSetBase {

   @Inject
   CanonicalRefNameMigrator migrator;

   @Override public String getId() { return "00010"; }
   @Override public String getDbFromVersion() { return "1.0.8"; }
   @Override public int getDbFromVersionInt() { return 108; }
   @Override public String getDbToVersion() { return "1.0.9"; }
   @Override public int getDbToVersionInt() { return 109; }
   @Override public int getPriority() { return 100; }
   @Override public String getAuthor() { return "Quantum Framework"; }
   @Override public String getName() { return "Report Non-Canonical RefNames"; }
   @Override public String getDescription() {
      return "Report UserGroup and Policy refNames that predate the canonical rule; apply per realm with "
            + "POST /system/migration/refnames/{realm}/apply.";
   }
   @Override public String getScope() { return "ALL"; }

   @Override
   public void execute(MorphiaSession session, MongoClient mongoClient, MultiEmitter<? super String> emitter) {
      RefNameMigrationPlan plan = migrator.plan(session.getDatabase().getName());
      log(String.format("Non-canonical refNames in %s: %d to rename, %d to merge, %d conflict, %d unresolvable%s",
            plan.realm(), plan.count(Kind.RENAME), plan.count(Kind.MERGE), plan.count(Kind.CONFLICT),
            plan.count(Kind.UNRESOLVABLE), plan.actions().isEmpty() ? ""
                  : "; review GET /system/migration/refnames/" + plan.realm()), emitter);
      plan.describe().forEach(line -> log("  would " + line, emitter));
   }
}
