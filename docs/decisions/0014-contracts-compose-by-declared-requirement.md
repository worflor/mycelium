# 0014 - Contracts compose by declared requirement

| | |
|---|---|
| Date | 2026-10-12 |
| Status | Accepted (`ContractRegistry` and `myceliumRepository` keep it; the plugin's self-tests break each rule and see it caught) |
| Amends | `docs/decisions/0005-what-a-contract-is-made-of.md` (a model mentions no other contract's package) |

## Context

Decision 0005 kept contract vocabularies apart: ten `Reason` enums, nine `Trait` enums and ten transaction classes share
a name and not a meaning, and a package that only existed to hold shared vocabulary would have no laws a do-nothing
implementation fails. The rule that kept this, "a model never mentions another contract's package", was written for that
danger. It also forbade something else: a contract that is built on another one. `item.presentation@1` reads the state
`item.component@1` defines (its component ids, its network visibility, its carrier snapshot). Forbidden from naming
those types, it would have to restate them, and two statements of one fact is the drift Mycelium exists to remove.

## Decision

A contract may require another, and the requirement is declared and checked.

- A registry row may carry `"requires": ["item.component@1"]`: exact ids, each once. `ContractRegistry` refuses an id that
  does not exist, that is retired, that is the contract itself, or any chain of requirements that leads back to where it
  started.
- A model may import the `model` package of a contract in its row's requirements, directly or through a requirement of a
  requirement, and nothing else of any other package. Its fixtures and harness are never reachable. `myceliumRepository`
  fails a model that reaches an undeclared package, or a required one's fixtures or harness.
- `myceliumImport` imports a contract's requirements with it, requirements first, under the consumer's namespace, so an
  imported model refers to the consumer's private copy of what it requires. `failOnUnused` does not count a contract
  that was imported only as a requirement. `myceliumVerify` and both runner scripts compile a package with its
  requirements' model sources, and the evidence manifest lists each contract's `requires`.
- Two contracts of which one supersedes the other are refused whichever of them came in as a requirement.

Vocabulary-only packages are still not admitted. A requirement is for a contract with laws of its own that builds on
another contract's laws; it is not a way to share a type.

## Consequences

- A consumer that selects `item.presentation@1` gets `item.component@1` as well, and need not list it.
- A major of a required contract (`item.component@2`) is a new row and a new requirement in the contracts that move to it;
  the old requirement stays until they do.
- Cycles are impossible by construction, so the import order is a plain topological order.
