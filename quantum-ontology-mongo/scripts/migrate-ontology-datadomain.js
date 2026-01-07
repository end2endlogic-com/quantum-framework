/**
 * Migration script to move ontology edges from tenantId-only scoping to full DataDomain scoping.
 * 
 * This script:
 * 1. Backfills dataDomain on edges lacking full context
 * 2. Drops old uniq_tenant_src_p_dst index
 * 3. Creates new uniq_domain_src_p_dst index with full DataDomain fields
 * 4. Validates counts and uniqueness
 * 
 * Usage:
 *   mongosh <connection-string> --quiet < migrate-ontology-datadomain.js
 *   OR
 *   node migrate-ontology-datadomain.js <connection-string> <database-name>
 * 
 * Example:
 *   mongosh "mongodb://localhost:27017/" --quiet < migrate-ontology-datadomain.js
 *   mongosh "mongodb://localhost:27017/system-com" migrate-ontology-datadomain.js
 */

// Configuration
const EDGES_COLLECTION = "edges";
const OLD_INDEX_NAME = "uniq_tenant_src_p_dst";
const NEW_INDEX_NAME = "uniq_domain_src_p_dst";

/**
 * Main migration function
 */
function migrateOntologyDataDomain(db, options = {}) {
    const dryRun = options.dryRun || false;
    const verbose = options.verbose || false;
    
    print(`\n=== Ontology DataDomain Migration ===`);
    print(`Database: ${db.getName()}`);
    print(`Dry run: ${dryRun ? 'YES' : 'NO'}\n`);
    
    const edgesCollection = db.getCollection(EDGES_COLLECTION);
    
    // Step 1: Analyze current state
    print("Step 1: Analyzing current edge state...");
    const totalEdges = edgesCollection.countDocuments({});
    print(`  Total edges: ${totalEdges}`);
    
    // Count edges missing dataDomain or with incomplete dataDomain
    const missingDomainCount = edgesCollection.countDocuments({
        $or: [
            { "dataDomain": { $exists: false } },
            { "dataDomain.orgRefName": { $exists: false } },
            { "dataDomain.accountNum": { $exists: false } },
            { "dataDomain.tenantId": { $exists: false } }
        ]
    });
    print(`  Edges missing/incomplete dataDomain: ${missingDomainCount}`);
    
    // Count edges with tenantId but missing other fields
    const tenantOnlyCount = edgesCollection.countDocuments({
        "dataDomain.tenantId": { $exists: true },
        $or: [
            { "dataDomain.orgRefName": { $exists: false } },
            { "dataDomain.accountNum": { $exists: false } }
        ]
    });
    print(`  Edges with tenantId but missing orgRefName/accountNum: ${tenantOnlyCount}\n`);
    
    if (missingDomainCount === 0 && tenantOnlyCount === 0) {
        print("✓ All edges already have complete DataDomain. Proceeding to index migration...\n");
    } else {
        // Step 2: Backfill strategy
        print("Step 2: Backfilling dataDomain...");
        
        if (tenantOnlyCount > 0) {
            // Strategy: For edges with tenantId but missing orgRefName/accountNum,
            // find other edges with the same tenantId that have complete DataDomain
            // and use the most common orgRefName/accountNum for that tenantId
            
            print(`  Finding common orgRefName/accountNum patterns per tenantId...`);
            
            const tenantPatterns = {};
            edgesCollection.find({
                "dataDomain.tenantId": { $exists: true },
                "dataDomain.orgRefName": { $exists: true },
                "dataDomain.accountNum": { $exists: true }
            }).forEach(edge => {
                const tenantId = edge.dataDomain.tenantId;
                const key = `${edge.dataDomain.orgRefName}|${edge.dataDomain.accountNum}|${edge.dataDomain.dataSegment || 0}`;
                
                if (!tenantPatterns[tenantId]) {
                    tenantPatterns[tenantId] = {};
                }
                tenantPatterns[tenantId][key] = (tenantPatterns[tenantId][key] || 0) + 1;
            });
            
            // For each tenant, find the most common pattern
            const tenantDefaults = {};
            for (const tenantId in tenantPatterns) {
                const patterns = tenantPatterns[tenantId];
                let maxCount = 0;
                let maxKey = null;
                for (const key in patterns) {
                    if (patterns[key] > maxCount) {
                        maxCount = patterns[key];
                        maxKey = key;
                    }
                }
                if (maxKey) {
                    const [orgRefName, accountNum, dataSegment] = maxKey.split('|');
                    tenantDefaults[tenantId] = {
                        orgRefName: orgRefName,
                        accountNum: accountNum,
                        dataSegment: parseInt(dataSegment) || 0
                    };
                    if (verbose) {
                        print(`    Tenant ${tenantId}: default pattern ${maxKey} (${maxCount} edges)`);
                    }
                }
            }
            
            // Backfill edges using tenant defaults
            let backfilledCount = 0;
            for (const tenantId in tenantDefaults) {
                const defaults = tenantDefaults[tenantId];
                
                const updateResult = edgesCollection.updateMany(
                    {
                        "dataDomain.tenantId": tenantId,
                        $or: [
                            { "dataDomain.orgRefName": { $exists: false } },
                            { "dataDomain.accountNum": { $exists: false } }
                        ]
                    },
                    {
                        $set: {
                            "dataDomain.orgRefName": defaults.orgRefName,
                            "dataDomain.accountNum": defaults.accountNum,
                            "dataDomain.dataSegment": defaults.dataSegment
                        }
                    }
                );
                
                if (!dryRun) {
                    backfilledCount += updateResult.modifiedCount;
                } else {
                    backfilledCount += updateResult.matchedCount;
                }
                
                if (verbose && updateResult.matchedCount > 0) {
                    print(`    Backfilled ${updateResult.matchedCount} edges for tenant ${tenantId}`);
                }
            }
            
            print(`  Backfilled ${backfilledCount} edges using tenant patterns\n`);
        }
        
        // Handle edges with no dataDomain at all - set to system defaults as fallback
        const noDomainCount = edgesCollection.countDocuments({
            "dataDomain": { $exists: false }
        });
        
        if (noDomainCount > 0) {
            print(`  Warning: ${noDomainCount} edges have no dataDomain at all.`);
            print(`  Setting system defaults (tenantId=system, orgRefName=system, accountNum=system)...`);
            
            const updateResult = edgesCollection.updateMany(
                { "dataDomain": { $exists: false } },
                {
                    $set: {
                        "dataDomain": {
                            "tenantId": "system",
                            "orgRefName": "system",
                            "accountNum": "system",
                            "dataSegment": 0,
                            "ownerId": "system"
                        }
                    }
                }
            );
            
            if (!dryRun) {
                print(`  Updated ${updateResult.modifiedCount} edges with system defaults\n`);
            } else {
                print(`  Would update ${updateResult.matchedCount} edges with system defaults\n`);
            }
        }
    }
    
    // Step 3: Drop old index
    print("Step 3: Managing indexes...");
    
    try {
        const indexes = edgesCollection.getIndexes();
        const hasOldIndex = indexes.some(idx => idx.name === OLD_INDEX_NAME);
        
        if (hasOldIndex) {
            if (dryRun) {
                print(`  Would drop old index: ${OLD_INDEX_NAME}`);
            } else {
                print(`  Dropping old index: ${OLD_INDEX_NAME}...`);
                edgesCollection.dropIndex(OLD_INDEX_NAME);
                print(`  ✓ Dropped old index\n`);
            }
        } else {
            print(`  Old index ${OLD_INDEX_NAME} not found (may have been dropped already)\n`);
        }
    } catch (e) {
        print(`  Error checking/dropping old index: ${e.message}\n`);
    }
    
    // Step 4: Create new index
    try {
        const indexes = edgesCollection.getIndexes();
        const hasNewIndex = indexes.some(idx => idx.name === NEW_INDEX_NAME);
        
        if (hasNewIndex) {
            print(`  New index ${NEW_INDEX_NAME} already exists`);
            
            if (!dryRun) {
                // Verify index structure
                const indexDef = indexes.find(idx => idx.name === NEW_INDEX_NAME);
                const requiredFields = [
                    "dataDomain.orgRefName",
                    "dataDomain.accountNum",
                    "dataDomain.tenantId",
                    "dataDomain.dataSegment",
                    "src",
                    "p",
                    "dst"
                ];
                
                const indexKeys = Object.keys(indexDef.key);
                const hasAllFields = requiredFields.every(field => indexKeys.includes(field));
                
                if (hasAllFields && indexDef.unique) {
                    print(`  ✓ New index exists and has correct structure\n`);
                } else {
                    print(`  ⚠ New index exists but may not have correct structure. Recreating...`);
                    edgesCollection.dropIndex(NEW_INDEX_NAME);
                    // Will create below
                }
            }
        }
        
        if (!hasNewIndex || (!dryRun && !indexes.find(idx => idx.name === NEW_INDEX_NAME))) {
            if (dryRun) {
                print(`  Would create new unique index: ${NEW_INDEX_NAME}`);
                print(`    Fields: dataDomain.orgRefName, dataDomain.accountNum, dataDomain.tenantId, dataDomain.dataSegment, src, p, dst\n`);
            } else {
                print(`  Creating new unique index: ${NEW_INDEX_NAME}...`);
                edgesCollection.createIndex(
                    {
                        "dataDomain.orgRefName": 1,
                        "dataDomain.accountNum": 1,
                        "dataDomain.tenantId": 1,
                        "dataDomain.dataSegment": 1,
                        "src": 1,
                        "p": 1,
                        "dst": 1
                    },
                    {
                        name: NEW_INDEX_NAME,
                        unique: true,
                        background: true
                    }
                );
                print(`  ✓ Created new index\n`);
            }
        }
    } catch (e) {
        print(`  Error creating new index: ${e.message}`);
        if (e.code === 11000 || e.codeName === 'DuplicateKey') {
            print(`  ⚠ Unique index violation detected. Some edges may have duplicate (src, p, dst) across DataDomains.`);
            print(`     This indicates existing data that violates the new constraint.`);
            print(`     Please review and resolve duplicates before retrying.\n`);
        } else {
            print(`  Full error: ${e}\n`);
        }
    }
    
    // Step 5: Validation
    print("Step 5: Validation...");
    
    const finalTotal = edgesCollection.countDocuments({});
    const finalMissing = edgesCollection.countDocuments({
        $or: [
            { "dataDomain": { $exists: false } },
            { "dataDomain.orgRefName": { $exists: false } },
            { "dataDomain.accountNum": { $exists: false } },
            { "dataDomain.tenantId": { $exists: false } }
        ]
    });
    
    print(`  Total edges: ${finalTotal}`);
    print(`  Edges with complete dataDomain: ${finalTotal - finalMissing}`);
    print(`  Edges still missing dataDomain: ${finalMissing}`);
    
    if (finalMissing > 0) {
        print(`  ⚠ Warning: ${finalMissing} edges still lack complete DataDomain. Manual review may be needed.`);
        
        // Show sample of problematic edges
        if (verbose) {
            print(`  Sample problematic edges:`);
            edgesCollection.find({
                $or: [
                    { "dataDomain": { $exists: false } },
                    { "dataDomain.orgRefName": { $exists: false } },
                    { "dataDomain.accountNum": { $exists: false } },
                    { "dataDomain.tenantId": { $exists: false } }
                ]
            }).limit(5).forEach(edge => {
                print(`    - src: ${edge.src}, p: ${edge.p}, dst: ${edge.dst}, dataDomain: ${JSON.stringify(edge.dataDomain || {})}`);
            });
        }
    }
    
    // Check for duplicates that would violate unique index
    print(`\n  Checking for potential unique index violations...`);
    const duplicateCheck = edgesCollection.aggregate([
        {
            $group: {
                _id: {
                    orgRefName: "$dataDomain.orgRefName",
                    accountNum: "$dataDomain.accountNum",
                    tenantId: "$dataDomain.tenantId",
                    dataSegment: "$dataDomain.dataSegment",
                    src: "$src",
                    p: "$p",
                    dst: "$dst"
                },
                count: { $sum: 1 }
            }
        },
        {
            $match: { count: { $gt: 1 } }
        },
        {
            $count: "duplicates"
        }
    ]).toArray();
    
    const duplicateCount = duplicateCheck.length > 0 ? duplicateCheck[0].duplicates : 0;
    if (duplicateCount > 0) {
        print(`  ⚠ Warning: Found ${duplicateCount} groups of duplicate edges that violate unique index.`);
        print(`     These must be resolved before the migration can complete.`);
    } else {
        print(`  ✓ No duplicate violations detected`);
    }
    
    print(`\n=== Migration ${dryRun ? '(DRY RUN) ' : ''}Complete ===\n`);
    
    return {
        totalEdges: finalTotal,
        missingDomainCount: finalMissing,
        duplicateCount: duplicateCount,
        success: finalMissing === 0 && duplicateCount === 0
    };
}

// Main execution
if (typeof db !== 'undefined') {
    // Running in mongosh
    if (typeof arguments !== 'undefined' && arguments.length > 0) {
        const dbName = arguments[0];
        const db = db.getSiblingDB(dbName);
        migrateOntologyDataDomain(db, { dryRun: false, verbose: true });
    } else {
        // Use current database
        migrateOntologyDataDomain(db, { dryRun: false, verbose: true });
    }
} else if (typeof require !== 'undefined') {
    // Running in Node.js
    const { MongoClient } = require('mongodb');
    
    const connectionString = process.argv[2] || 'mongodb://localhost:27017/';
    const dbName = process.argv[3] || 'system-com';
    
    async function runMigration() {
        const client = new MongoClient(connectionString);
        try {
            await client.connect();
            const db = client.db(dbName);
            
            const result = migrateOntologyDataDomain(db, { dryRun: false, verbose: true });
            
            if (!result.success) {
                process.exit(1);
            }
        } catch (e) {
            print(`Error: ${e.message}`);
            process.exit(1);
        } finally {
            await client.close();
        }
    }
    
    runMigration();
} else {
    print("Error: This script must be run in mongosh or Node.js with mongodb driver");
    if (typeof quit !== 'undefined') {
        quit(1);
    }
}



