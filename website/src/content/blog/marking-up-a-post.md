---
title: Marking up a post
blurb: Every highlighter and squiggle the site knows, shown in context.
date: 2026-08-27
colour: lemon
---

This post exists to show what the inline syntax looks like once it renders.
Everything below is written in plain Markdown — the marks are ==onion:part of
the prose==, not screenshots.

## Highlighter marks

A mark is a wash of colour sitting behind the words, the way a highlighter
behaves on paper. There are five, and they read best on a short phrase rather
than a whole sentence.

Effects in Mezze are ==onion:part of the type==, not a convention. A record is
==lemon:structural== — two records with the same fields are the same type,
whatever you called them. Abilities are ==auber:resolved at compile time==, so
dispatch costs nothing at runtime. The whole compiler ==mint:runs in your
browser== on this site. And the typechecker will ==olive:refuse to guess== when
it cannot prove a thing.

## Squiggles

A squiggle is a hand-drawn underline. It is quieter than a mark, so it suits a
single word you want to lean on rather than a phrase you want to stop on.

The default squiggle picks up ~the current dish colour~, so it shifts when you
click the mascot. Give it a name and it stays put: ~olive:olive~,
~aubergine:aubergine~, ~tomato:tomato~, ~lemon:lemon~, ~onion:onion~,
~mint:mint~.

## Together, in something real

Here is a paragraph written the way an actual post would be — a mark for the
claim, a squiggle for the qualifier.

Mezze compiles to WebAssembly through GraalVM Web Image, which means the
playground is ==onion:the real compiler== rather than a simulation. Parsing,
typechecking, evaluation, and outbound HTTPS all work. Threads do not —
`async` rests on Loom, Loom rests on `Unsafe.park`, and WebAssembly has
~no threading story that ships today~. Those handlers are denied at the
boundary, so they report themselves instead of ==tomato:failing somewhere
confusing==.

## Showing the syntax itself

Anything inside backticks is left alone, so you can write about the markup
without triggering it: `==onion:word==` and `~tomato:word~` stay as text.

Fenced blocks are protected too:

```
==onion:word==     mark
~word~             squiggle
~tomato:word~      coloured squiggle
```

That is the whole system. Marks take `==name:text==`, squiggles take `~text~`
with an optional `~name:text~` for colour.
