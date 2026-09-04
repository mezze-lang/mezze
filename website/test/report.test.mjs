/**
 * Warnings must not stop a program running.
 *
 * `report()` gates the Run button: it returned false for ANY diagnostic, so a
 * single unused import — `use std::effects::http::{ Http, request }` where
 * only `request` is named — printed an error where the output should be. The
 * program itself was always fine.
 *
 * This mirrors report()'s severity rule rather than importing it, since the
 * real one is a closure inside mountEditor and needs a DOM.
 */
import { test, describe } from 'node:test'
import assert from 'node:assert/strict'

/** true when the source is runnable: warnings are shown, errors block. */
const runnable = (diagnostics) =>
  diagnostics.filter((d) => d.severity !== 'warning').length === 0

describe('report gating', () => {
  test('clean source runs', () => {
    assert.equal(runnable([]), true)
  })

  test('a warning does NOT block the run', () => {
    assert.equal(runnable([
      { severity: 'warning', message: "Unused import: 'Http' is never referenced" },
    ]), true)
  })

  test('several warnings still do not block', () => {
    assert.equal(runnable([
      { severity: 'warning', message: "Unused import: 'Http'" },
      { severity: 'warning', message: "Unused import: 'FileSystem'" },
    ]), true)
  })

  test('an error blocks', () => {
    assert.equal(runnable([
      { severity: 'error', message: 'Type mismatch' },
    ]), false)
  })

  test('an error blocks even alongside warnings', () => {
    assert.equal(runnable([
      { severity: 'warning', message: "Unused import: 'Http'" },
      { severity: 'error', message: 'Type mismatch' },
    ]), false)
  })

  test('a diagnostic with no severity is treated as an error', () => {
    // The envelope omits severity on some paths; the safe reading is "error".
    assert.equal(runnable([{ message: 'something went wrong' }]), false)
  })
})
