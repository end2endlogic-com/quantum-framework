# Auth provider compatibility gate

Compile `LegacyAuthProviderProbe` against the released models JAR and execute
that unchanged bytecode with candidate classes. The probe exercises old provider
implementations, all three positive-response constructors, default scoped-login
overloads, and the existing unsupported-realm denial. It is a focused linkage
gate, not certification of the whole Java or HTTP API.

Resolve the baseline using Maven's declared repositories, then run:

```sh
mvn dependency:get -Dartifact=com.end2endlogic:quantum-models:1.4.2
python3 scripts/check-auth-provider-compatibility.py \
  --baseline-jar "$HOME/.m2/repository/com/end2endlogic/quantum-models/1.4.2/quantum-models-1.4.2.jar" \
  --baseline-sha256 dfb38e46de891b4f6b7119e57f5bd75fc164cfe85ff7d822f566b38a942a9161
```

The hash identifies the reviewed baseline artifact. A different artifact fails
closed; investigate provenance before changing it. The script builds the candidate
models and uses Maven's dependency classpath. No sibling source paths or old models
JAR are placed on the runtime classpath. A failed compile, linkage or assertion
fails the command.

The existing `OAuthTokenResourceTest` checks the OAuth wire field names and cache
headers. Keep those assertions when changing validation. A security correction
must not change the OAuth error envelope, endpoint signature or response record.

Additional release gates remain required: whole-public-API ABI comparison,
historical generated Java/TypeScript/Python client execution, storage round trips,
real HTTP/security middleware tests and mixed-version issuer verification.

## OAuth validation behavior

Public clients cannot use `client_credentials`. Authorization-code requests for
public clients require a 43-character S256 challenge on both GET and form POST;
exchange requires the matching 43–128 character verifier. Validation happens
before authentication or authorization-code consumption. Confidential clients
retain the no-PKCE flow; supplied challenges must use S256.

This preserves method signatures and the OAuth response/error shapes, but is an
intentional security behavior change for insecure registrations. Inventory those
registrations before activation. Do not retain a bypass to satisfy compatibility.
`OAuthHttpValidationTest` verifies the token error JSON and authorization form
redirect through Quarkus HTTP with a mocked client repository. It does not prove
production registration compatibility, persistence or multi-node behavior.
