# Verbose comments policy

Code in this repository is maintained by humans and by coding agents. Comments are part of the contract. A later editor must be able to tell, from the file itself, what the code does, what it must not be assumed to do, and which defects are intentional leftovers versus accidents.

1. Every hand-written source file starts with a file-level comment (KDoc, or an XML comment) stating its role, the direction of control (computer runs the Barrier server and owns the keyboard and mouse; the phone is the client), and the known gaps in that file. Generated launcher art, backup XML, and theme stubs are exempt.
2. Public types, functions, and properties get KDoc. Include units, wire direction, which thread calls them, and what failure returns. Do not restate the signature.
3. Comment the invariant, the protocol fact, or the platform limit — not the syntax. "Increment i" is noise. "Barrier key ids are not Android key codes" is required.
4. Known defects stay commented at the defect until the defect is fixed. Do not delete the comment and leave the bug. Do not rewrite a comment to describe a design that the code does not implement.
5. Protocol comments name the command (`QINF`, `DCLP`, …), the direction, and the field widths as this codec reads them. If that diverges from Barrier's C++ sources, say so beside the field.
6. Behavior changes and comment changes land in the same edit. A stale comment is a bug.
7. Tests comment the scenario and any clock or threading trick (`runCurrent` versus `advanceTimeBy`). They do not narrate each assertion.
8. Comments are complete English sentences and safe to quote in a handoff. A `TODO` must say what is blocked and by what. No comment that only a previous session would understand.
9. Do not use comments to hide missing behavior. If a function ignores its argument, the comment says it ignores the argument.

This file and `/home/chris/.opencode/plan/barrier-android-client.md` must be edited together.
