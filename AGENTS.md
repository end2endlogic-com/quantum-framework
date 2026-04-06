# Repository Notes

## Related Repositories

- The framework Cognito provider source is located at `/Users/mingardia/dev/mrisys/end2endlogic/quantum/framework-cognito-provider`.
- The Quantum Enterprise source is located at `/Users/mingardia/dev/mrisys/end2endlogic/quantum/quantum-enterprise`.

## Dependency Boundaries

- The open-source framework must not depend on `quantum-enterprise`.
- `quantum-enterprise` is optional and commercially licensed, while this framework is open source.
- Keep enterprise-specific behavior and integrations outside the framework unless they can remain fully optional and decoupled.
