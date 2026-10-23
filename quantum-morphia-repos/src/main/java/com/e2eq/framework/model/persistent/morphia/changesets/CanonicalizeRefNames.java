package com.e2eq.framework.model.persistent.morphia.changesets;

import com.e2eq.framework.model.persistent.migration.base.ChangeSetBase;
import com.e2eq.framework.model.persistent.migration.refname.CanonicalRefNameMigrator;
import com.e2eq.framework.model.persistent.migration.refname.RefNameMigrationPlan;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.mongodb.client.MongoClient;
import dev.morphia.transactions.MorphiaSession;
import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Locale;

/**
 * Renames stored UserGroup and Policy refNames that predate the canonical rule (see {@link CanonicalRefNameMigrator}).
 *
 * <p>{@code quantum.refname.migration.mode} is {@code report} by default: the change set computes the plan for the
 * realm and logs it, changing nothing ({@code GET /system/migration/refnames/{realm}} returns the same plan). With
 * {@code apply} it performs the renames and merges and reports what it left for an operator. The mode is part of
 * the checksum, so switching to {@code apply} re-runs the change set on every realm.</p>
 */
@Startup
@ApplicationScoped
public class CanonicalizeRefNames extends ChangeSetBase {

   static final String REPORT = "report";
   static final String APPLY = "apply";

   @ConfigProperty(name = "quantum.refname.migration.mode", defaultValue = REPORT)
   String mode;

   @Inject
   CanonicalRefNameMigrator migrator;

   @Inject
   MorphiaDataStoreWrapper morphiaDataStoreWrapper;

   @Override public String getId() { return "00010"; }
   @Override public String getDbFromVersion() { return "1.0.8"; }
   @Override public int getDbFromVersionInt() { return 108; }
   @Override public String getDbToVersion() { return "1.0.9"; }
   @Override public int getDbToVersionInt() { return 109; }
   @Override public int getPriority() { return 100; }
   @Override public String getAuthor() { return "Quantum Framework"; }
   @Override public String getName() { return "Canonicalize RefNames"; }
   @Override public String getDescription() {
      return "Rename UserGroup and Policy refNames that predate the canonical rule (quantum.refname.migration.mode="
            + "report|apply).";
   }
   @Override public String getScope() { return "ALL"; }
   @Override public String getChecksum() { return "canonicalize-refnames-v1-" + mode(); }

   String mode() {
      String value = mode == null ? REPORT : mode.trim().toLowerCase(Locale.ROOT);
      if (!REPORT.equals(value) && !APPLY.equals(value)) {
         throw new IllegalStateException("quantum.refname.migration.mode must be 'report' or 'apply', not '" + mode + "'");
      }
      return value;
   }

   @Override
   public void execute(MorphiaSession session, MongoClient mongoClient, MultiEmitter<? super String> emitter) {
      String realm = session.getDatabase().getName();
      RefNameMigrationPlan plan = migrator.plan(morphiaDataStoreWrapper.getDataStore(realm));
      log(String.format("refName migration (%s) in %s: %d rename, %d merge, %d conflict, %d unresolvable", mode(),
            realm, plan.count(RefNameMigrationPlan.Kind.RENAME), plan.count(RefNameMigrationPlan.Kind.MERGE),
            plan.count(RefNameMigrationPlan.Kind.CONFLICT), plan.count(RefNameMigrationPlan.Kind.UNRESOLVABLE)),
            emitter);
      if (APPLY.equals(mode())) {
         migrator.apply(session, plan, message -> log("  " + message, emitter));
      } else {
         plan.describe().forEach(line -> log("  would " + line, emitter));
      }
   }
}
