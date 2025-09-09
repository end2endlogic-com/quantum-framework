# Quantum

Provides AUTHZ and AUTHN for quarkus in a multi-tenant SAAS context

## Getting Started:

### Setting up your environment:
You will need to create a .env file with your local settings and credentials
There is a template file located in the root.  Copy this to .env and fill in the values
```
MONGODB_USERNAME= {{ YOUR USER ID }}
MONGODB_PASSWORD= {{ YOUR PASSWORD }}
MONGODB_DATABASE= {{ YOUR DATABASE }}
MONGODB_HOST= {{ YOUR HOST }}
MONGODB_DEFAULT_SCHEMA={{ YOUR SCHEMA }}
POSTMARK_API_KEY= {{ YOUR KEY }}
POSTMARK_DEFAULT_FROM_EMAIL= {{ YOUR EMAIL ADDRESS }}
POSTMARK_DEFAULT_TO_EMAIL={{ YOUR EMAIL ADDRESS }}
AWS_ROLE_ARN= {{ YOUR ARN }}
AWS_REGION= {{ YOUR REGION }}
QUARKUS_HTTP_CORS_ORIGINS={{ YOUR COMMA SEPERATED LIST OF HOSTS }}

```

### Running the application in dev mode

You can run your application in dev mode that enables live coding using:
```shell script
./mvnw compile quarkus:dev
```

> **_NOTE:_**  Quarkus now ships with a Dev UI, which is available in dev mode only at http://localhost:8080/q/dev/.

## Packaging and running the application

The application can be packaged using:
```shell script
./mvnw package
```
It produces the `quarkus-run.jar` file in the `target/quarkus-app/` directory.
Be aware that it’s not an _über-jar_ as the dependencies are copied into the `target/quarkus-app/lib/` directory.

The application is now runnable using `java -jar target/quarkus-app/quarkus-run.jar`.

If you want to build an _über-jar_, execute the following command:
```shell script
./mvnw package -Dquarkus.package.type=uber-jar
```

The application, packaged as an _über-jar_, is now runnable using `java -jar target/*-runner.jar`.

## Creating a native executable

You can create a native executable using:
```shell script
./mvnw package -Pnative
```

Or, if you don't have GraalVM installed, you can run the native executable build in a container using:
```shell script
./mvnw package -Pnative -Dquarkus.native.container-build=true
```

You can then execute your native executable with: `./target/code-with-quarkus-1.0.0-SNAPSHOT-runner`

If you want to learn more about building native executables, please consult https://quarkus.io/guides/maven-tooling.


To Connect to Mongodb:
Shell - Assuming severless mongodb
```shell
mongosh "mongodb+srv://<host>/<database>" --apiVersion 1 --username admin
```
JDBC version 4.3 or later
```
mongodb+srv://admin:<password>@<host>/<database>?retryWrites=true&w=majority
```

To create a local Mongodb instances:
data is located in ~/data
``` shell
mongod --wiredTigerCacheSizeGB 1 --dbpath /Users/<your userId>/data
```


## General Overview of the framework

### Creation of REST API's backed by a MONGO Database

### Models

#### Base Model Functionality
##### ID's, RefNames, DisplayNames, AuditInfo
#### References + Validation
##### Lazy Initialization
#### Dates
#### Money / Currency
#### Internationalization / I18N / I10N
#### Data Creation + Data Domain Policies
#### Data Life Cycle Management
#### Schema Migrations
#### Dynamic AttributeSets & Attributes

### Repos
#### Base Repo Functionality
#### Overriding common actions
#### Event Subsystem and publish / subscribe
#### Hooks, and call outs

### Resources
#### Base Resource Functionality
#### REST Conventions
#### Error Handling and Responses
#### LIST API Conventions
The List API supports query parameters for filtering, pagination, sorting, and projection.
To sort results, supply a `sort` parameter with comma-separated field names prefixed by `+` for ascending
or `-` for descending order. For example: `?sort=-name,+id` sorts by name descending and id ascending.
To limit returned fields, use the `projection` parameter with a comma-separated list of fields to include or
exclude. Prefix a field with `+` to include it or `-` to exclude it, e.g. `?projection=+id,+name,-internalNotes`.
#### Search API Conventions
#### Invalid / Valid Object Persistence
#### Merge vs. Replace Update Models
#### File Operations / Signed URLS
#### Async API's / Websockets
#### Push API's / Webhooks
#### JAXRS Filters
#### AUTHN
#### Public vs. Secured API's

### Security Model

*Organization* - An organization is the top level object that 
owns resources.  It is associated with a root email that can administer 
everything that organization owns.  When you invite users to use your resources
your inviting them to join your organization.

*Account* - General Concept of an account that is 
itis a container of resources, and consolidates billing and cost
/ invoices related to those resources.  It can also be used to segement
resource access, so users in an organization may only be able to access certain accounts
and not other accounts, thus segmenting resource access.  A common use case
would be to allocate accounts as sandboxes, dev, test, prod environments, or dedicated
accounts for specific customers, projects etc.

*Functional Domain* - A functional domain is a category of functionality such as 
security, user administration, order management, etc.  With in a functional domain
certain actions can be take such as create orders, change passwords, update user profiles etc.
Its up to the application how they wil categorize their functionality but ultimately the functional domain
is used as a means to determine authorization when combined with an action and a data domain.
The general combination is a permission.  So you can create permissions for a user / role to take a specific 
action with in a functional domain, on a specific data domain ( more on data domains below )

*Functional Action* - These are actions that can be taken with in a functional domain.  The most obvious 
example is to create a functional domain for an entity / table / collection in a database say a UserProfile and
then create actions such as Create, Update, Delete, View but you may also add things like
change password, archive, etc.

*Data Domain* - This defines / segments data in a given database and allows you to control access to just 
specific data domains.  For example you may want to have a segmentation process where you have multiple customers
share a single database, but you want to associate each one with a tenant_id or a specific account, org, geographical location / region,
or user.  You can for example create models that say when a user creates a file they are the only ones that have 
full rights on that file.  They can then designate that they want share the file with others in doing so a permission
can be created for that other user to view the file.  Alternatively you can set up unix posfix like concepts and create 
separate data segments for a user such as private, group, and public and then tag rows as either public, group, or private.


*Realm* - Realms are groupings of user profiles.  You can define a realm to be backed by a local set of tables, an Identity Provider, such as a SAML 
provider, or OIDC compliant system.

## AUTHN
### Distributed Trust

## AUTHZ

*Policy* - Security policies are a rule base that define what users / roles can take what actions wiht in what functional domains
on some data domain.

## Example

### SecurityModel.yaml
```yaml
-
displayName: UserProfile
refName: USER_PROFILE
functionalActions:
-
displayName: Change Password
refName: CHANGE_PASSWORD
-
displayName: Disable
refName: DISABLE
-
displayName: Enable
refName: ENABLE
-
displayName: View
refName: VIEW
-
displayName: Create
refName: CREATE
-
displayName: Update
refName: UPDATE
-
displayName: Archive
refName: ARCHIVE
-
displayName: Delete
refName: DELETE
```
Here we are defining a UserProfile Functional domain and allowing various actions

### SecurityRules.yaml
```yaml
  -
      name: an accountAdmin can take any action on any entity in their account
      description: allow accountAdmins to administer the account
      securityURI:
        header:
          identity: accountAdmin
          area: '*'
          functionalDomain: '*'
          action: '*'
        body:
          realm: b2bi
          accountNumber: '*'
          tenantId: '*'
          dataSegment: '*'
          ownerId: '*'
          resourceId: '*'
      preconditionScript:
      postconditionScript: pcontext.accountId === rcontext.accountId
      effect: ALLOW
      priority: 90
      finalRule: true

```
This defines a rule that allows an accountAdmin ( role )
to take any action, on any functional domain, with in a specific realm
where the accountId matches the accountId of the resource.  In otherwords
they can administer all functionality with in any account that is goverened by 
a certain realm.











## Completion Tasks

The framework exposes endpoints to manage groups of completion tasks.

### Creating a group

```bash
curl -X POST http://localhost:8080/integration/completionTaskGroup/create \
     -H "Content-Type: application/json" \
     -d '{"refName":"demo","displayName":"Demo group"}'
```

### Adding a task to a group

```bash
curl -X POST http://localhost:8080/integration/completionTask/create/{groupId} \
     -H "Content-Type: application/json" \
     -d '{"refName":"task1","displayName":"First task"}'
```

### Checking task status

```bash
curl http://localhost:8080/integration/completionTask/id/{taskId}
```

### Subscribing for completion events

```bash
curl http://localhost:8080/integration/completionTaskGroup/subscribe/{groupId}
```
