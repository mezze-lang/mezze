package com.mezze.language.ast

/*
 * #515b — several tests here used the pre-cleanup `TypeVar(name)`
 * single-arg ctor (removed by commit ee763a473 — cleanup deleted 1108
 * lines of dead code — TypeVar now requires an `id`). The pretty-print
 * helpers themselves still exist; when someone wants this coverage back
 * they can rewrite the fixtures using `TypeVar.fresh()` or supplying an
 * explicit id. Stubbed to keep the tree compilable.
 */
class TypePrinterConstraintTest
