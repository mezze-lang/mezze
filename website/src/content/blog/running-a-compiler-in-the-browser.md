---
title: Running a compiler in the browser
blurb: Mezze parses, typechecks and runs inside WebAssembly — including real HTTPS.
date: 2026-08-20
colour: olive
draft: true
---

The playground on this site is ==onion:not a simulation==. It is the actual Mezze
compiler — parser, typechecker, and evaluator — compiled to WebAssembly with
GraalVM Web Image and running in your tab.

## What works

Parsing, typechecking, evaluation, algebraic effects, and outbound HTTPS.
A program is about 110ms end to end after a cold start of roughly 850ms.

## What does not

Anything that needs threads. `async` and `parallel` rest on Loom, Loom rests on
`Unsafe.park`, and WebAssembly has no threading story that ships today. Those
handlers are denied at the boundary and report themselves rather than failing
somewhere confusing.

Sequential STM works, which was ~tomato:a surprise~.
