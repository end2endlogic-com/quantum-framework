# Ontology DataDomain Migration Script

This directory contains migration scripts for the Ontology DataDomain refactor.

## migrate-ontology-datadomain.js

Migrates ontology edges from `tenantId`-only scoping to full `DataDomain` scoping (orgRefName, accountNum, tenantId, dataSegment).

### What it does

1. **Analyzes current state**: Counts edges with missing or incomplete DataDomain
2. **Backfills DataDomain**: Uses statistical patterns to infer missing DataDomain fields from edges with the same tenantId
3. **Drops old index**: Removes the old `uniq_tenant_src_p_dst` index
4. **Creates new index**: Creates the new `uniq_domain_src_p_dst` unique index with full DataDomain fields
5. **Validates**: Checks for remaining missing DataDomains and duplicate violations

### Usage

#### Using mongosh (MongoDB Shell)

```bash
# Connect and run for specific database
mongosh "mongodb://localhost:27017/system-com" < migrate-ontology-datadomain.js

# Or connect interactively and source the script
mongosh "mongodb://localhost:27017/system-com"
> load("migrate-ontology-datadomain.js")
```

#### Using Node.js

Requires `mongodb` npm package:

```bash
npm install mongodb
node migrate-ontology-datadomain.js <connection-string> <database-name>

# Example:
node migrate-ontology-datadomain.js "mongodb://localhost:27017/" "system-com"
```

### Dry Run

To test the migration without making changes, modify the script to set `dryRun: true` in the `migrateOntologyDataDomain` call.

### Important Notes

1. **Run per realm/database**: This script operates on a single database. Run it for each realm/database that contains ontology edges.

2. **Backup first**: Always backup your database before running migrations.

3. **Review edge cases**: If edges are missing DataDomain entirely, they will be set to system defaults. Review the verbose output to identify edges that may need manual correction.

4. **Duplicate handling**: If the script detects duplicate edges that would violate the unique index, you must resolve these manually before the migration can complete.

5. **Downtime**: Index creation may require a write lock. Plan for brief downtime during index creation on large collections.

### Expected Output

```
=== Ontology DataDomain Migration ===
Database: system-com
Dry run: NO

Step 1: Analyzing current edge state...
  Total edges: 15234
  Edges missing/incomplete dataDomain: 234
  Edges with tenantId but missing orgRefName/accountNum: 234

Step 2: Backfilling dataDomain...
  Finding common orgRefName/accountNum patterns per tenantId...
  Backfilled 234 edges using tenant patterns

Step 3: Managing indexes...
  Dropping old index: uniq_tenant_src_p_dst...
  ✓ Dropped old index
  Creating new unique index: uniq_domain_src_p_dst...
  ✓ Created new index

Step 5: Validation...
  Total edges: 15234
  Edges with complete dataDomain: 15234
  Edges still missing dataDomain: 0
  ✓ No duplicate violations detected

=== Migration Complete ===
```

### Troubleshooting

**Error: DuplicateKey (E11000)**
- Some edges have the same (src, p, dst) across different DataDomains, which would violate the unique index.
- Investigate and deduplicate these edges manually.
- You may need to review the data to understand which edges should be kept or merged.

**Warning: Edges still missing dataDomain**
- The statistical backfill couldn't determine DataDomain for some edges.
- Review the verbose output to see which edges need manual correction.
- Consider querying source entities' DataDomain directly if you know the entity types.

**Index creation fails or times out**
- Large collections may take time to build indexes.
- Consider building indexes in the background (already enabled in script).
- For very large collections, you may need to create the index manually with `background: true` option.



