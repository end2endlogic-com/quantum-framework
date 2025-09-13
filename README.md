# GitHub Pages Branch

This branch is managed by the Maven SCM Publish Plugin (`maven-scm-publish-plugin`).
Do not edit files here manually. Run this from the default branch instead:

    mvn -pl quantum-docs -am -DskipTests deploy

That command builds the docs to `quantum-docs/target/site` and publishes those files to `gh-pages`.
