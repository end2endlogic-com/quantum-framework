# Quantum Framework Sales Materials

This repository contains sales and marketing materials for the Quantum Framework, positioning it as a "Palantir Light" solution for organizations seeking enterprise-grade data integration and governance capabilities.

## Overview

This is a standalone module that can be placed in a separate git repository. It contains:

- Sales documents comparing Quantum to Palantir
- Framework overview and advantages
- Use cases and positioning materials
- Infrastructure for generating PDF documentation

## Building the Documentation

### Prerequisites

- Java 17+
- Maven 3.6+

### Build Commands

Generate PDF documentation:

```bash
mvn clean package
```

The generated PDF files will be in `target/site/`:
- `target/site/index.pdf` - Main sales document (includes all sections)
- Individual PDF files for each document section

## Structure

```
quantum-sales/
├── pom.xml                    # Maven build configuration
├── package.json               # Node.js dependencies (optional)
├── README.md                  # This file
├── scripts/
│   └── init-gh-pages.sh      # (Not used - kept for reference only)
└── src/
    └── docs/
        └── asciidoc/
            ├── index.adoc                    # Main index
            └── sales/
                ├── quantum-vs-palantir.adoc  # Comparison document
                └── framework-overview.adoc   # Framework overview
```

## Key Documents

### Quantum vs. Palantir

The main sales document (`sales/quantum-vs-palantir.adoc`) positions Quantum as "Palantir Light" by:

- Explaining what Quantum is and its core capabilities
- Comparing Quantum to Palantir feature-by-feature
- Highlighting when to choose Quantum vs. Palantir
- Providing real-world use cases
- Demonstrating cost and complexity advantages

### Framework Overview

The framework overview (`sales/framework-overview.adoc`) provides:

- High-level description of Quantum
- Key advantages and capabilities
- Architecture highlights
- Use cases and competitive positioning
- Getting started guidance

## Customization

This module is designed to be standalone and can be:

- Placed in a separate git repository
- Customized for specific sales scenarios
- Extended with additional sales materials
- Branded for your organization

To customize:

1. Update `pom.xml` with your organization details
2. Modify the AsciiDoc files in `src/docs/asciidoc/sales/`
3. Add additional documents as needed
4. Update this README with your specific instructions

## License

This sales module follows the same license as the Quantum Framework (Apache License 2.0).

## Support

For questions about the Quantum Framework, see the main framework repository or documentation.

